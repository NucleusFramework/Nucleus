@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package dev.nucleusframework.speech

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import platform.AVFAudio.AVAudioEngine
import platform.AVFAudio.AVAudioEngineConfigurationChangeNotification
import platform.AVFAudio.AVAudioPCMBuffer
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryAmbient
import platform.AVFAudio.AVAudioSessionCategoryOptionAllowBluetooth
import platform.AVFAudio.AVAudioSessionCategoryOptionDefaultToSpeaker
import platform.AVFAudio.AVAudioSessionCategoryOptionMixWithOthers
import platform.AVFAudio.AVAudioSessionCategoryOptions
import platform.AVFAudio.AVAudioSessionCategoryPlayAndRecord
import platform.AVFAudio.AVAudioSessionCategorySoloAmbient
import platform.AVFAudio.AVAudioSessionInterruptionNotification
import platform.AVFAudio.AVAudioSessionModeDefault
import platform.AVFAudio.AVAudioSessionModeMeasurement
import platform.AVFAudio.AVAudioSessionSetActiveOptionNotifyOthersOnDeactivation
import platform.AVFAudio.setActive
import platform.AVFoundation.AVCaptureDevice
import platform.AVFoundation.AVMediaTypeAudio
import platform.AVFoundation.requestAccessForMediaType
import platform.Foundation.NSBundle
import platform.Foundation.NSError
import platform.Foundation.NSLocale
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSQualityOfServiceUserInitiated
import platform.Foundation.NSSelectorFromString
import platform.Foundation.localeIdentifier
import platform.Speech.SFSpeechAudioBufferRecognitionRequest
import platform.Speech.SFSpeechRecognitionResult
import platform.Speech.SFSpeechRecognitionTask
import platform.Speech.SFSpeechRecognitionTaskHintDictation
import platform.Speech.SFSpeechRecognizer
import platform.Speech.SFSpeechRecognizerAuthorizationStatus
import platform.darwin.NSObjectProtocol
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

// Apple documents a one-minute task limit: ask for a final result before it.
private const val ROLLOVER_SECONDS = 55.0
private const val FINALIZATION_TIMEOUT_SECONDS = 3.0
private const val RESTART_DELAY_SECONDS = 0.15
private const val TAP_BUFFER_FRAMES = 1024u
private const val METER_RATE_HZ = 30.0

// Apple's documented "no speech detected" error; generic failures and authorization/network
// errors stay visible. https://developer.apple.com/documentation/speech/sfspeechrecognitiontask/error
private const val NO_SPEECH_DOMAIN = "kAFAssistantErrorDomain"
private const val NO_SPEECH_CODE = 1110L
private const val MAX_NO_SPEECH_RETRIES = 3
private const val NO_SPEECH_BASE_DELAY_SECONDS = 0.25

private const val SPEECH_PERMISSION_DENIED =
    "Speech recognition permission was denied. " +
        "Allow speech recognition for this app in system privacy settings."

private const val MICROPHONE_PERMISSION_DENIED =
    "Microphone permission was denied. " +
        "Allow microphone access for this app in system privacy settings."

private const val LANGUAGE_UNAVAILABLE =
    "Speech recognition is unavailable for the current language. " +
        "Check the system speech settings and network connection."

private const val RESUME_TIMEOUT =
    "Speech recognition took too long to resume. " +
        "Your draft has been kept; please start dictation again."

// The iOS 26 SDK renamed AllowBluetooth to AllowBluetoothHFP; the value is the same.
@Suppress("DEPRECATION")
private val speechAudioOptions: AVAudioSessionCategoryOptions =
    AVAudioSessionCategoryOptionDefaultToSpeaker or AVAudioSessionCategoryOptionAllowBluetooth or
        AVAudioSessionCategoryOptionMixWithOthers

private data class AudioConfiguration(
    val category: String?,
    val mode: String?,
    val options: AVAudioSessionCategoryOptions,
)

/**
 * One dictation session: asks for both permissions, owns the microphone, and restarts the
 * recognizer between utterances. A Kotlin/Native port of robius-speech's `NativeSpeech.swift`.
 * Everything here runs on the main queue, except the audio tap, which only talks to [capture].
 */
@Suppress("TooManyFunctions")
internal class IosSpeechSession(
    private val id: Long,
    options: SpeechRecognitionOptions,
) {
    private val locale = options.locale?.takeIf(String::isNotEmpty)?.let { NSLocale(localeIdentifier = it) }
    private val preferOnDevice = options.preferOnDevice

    // No audio hardware is touched before the privacy checks and permissions.
    private var engine: AVAudioEngine? = null
    private val capture = AudioCapture()
    private var recognizer: SFSpeechRecognizer? = null
    private var request: SFSpeechAudioBufferRecognitionRequest? = null
    private var task: SFSpeechRecognitionTask? = null
    private var stopping = false
    private var finished = false
    private var tapInstalled = false
    private var started = false
    private var segments = TranscriptSegments()
    private var generation = 0L
    private var noSpeechRetries = 0
    private var endingUtterance = false
    private var rollover: Delayed? = null
    private var pendingRestart: Delayed? = null
    private val observers = mutableListOf<NSObjectProtocol>()
    private var previousAudio: AudioConfiguration? = null
    private var activatedAudioSession = false

    private fun send(event: SpeechEvent) {
        if (!finished) SpeechSessions.emit(id, event)
    }

    fun authorize() {
        // Apple's APIs terminate the process when a privacy description is missing: report an
        // actionable error before either permission prompt.
        for (key in listOf("NSMicrophoneUsageDescription", "NSSpeechRecognitionUsageDescription")) {
            val reason = NSBundle.mainBundle.objectForInfoDictionaryKey(key) as? String
            if (reason.isNullOrEmpty()) {
                fail("This app is missing its $key privacy description.", SpeechErrorKind.PermissionDenied)
                return
            }
        }
        SFSpeechRecognizer.requestAuthorization { status ->
            onMain {
                if (finished || stopping) return@onMain
                if (status != SFSpeechRecognizerAuthorizationStatus.SFSpeechRecognizerAuthorizationStatusAuthorized) {
                    fail(
                        SPEECH_PERMISSION_DENIED,
                        SpeechErrorKind.PermissionDenied,
                    )
                    return@onMain
                }
                AVCaptureDevice.requestAccessForMediaType(AVMediaTypeAudio) { granted ->
                    onMain {
                        if (finished || stopping) return@onMain
                        if (granted) {
                            start()
                        } else {
                            fail(
                                MICROPHONE_PERMISSION_DENIED,
                                SpeechErrorKind.PermissionDenied,
                            )
                        }
                    }
                }
            }
        }
    }

    private fun start() {
        // The system's default speech language rather than a region-based current locale.
        // initWithLocale: returns nil for an unsupported locale, which Kotlin types as non-null.
        val selected =
            when {
                locale == null -> SFSpeechRecognizer()
                isSupportedLocale(locale) -> SFSpeechRecognizer(locale = locale)
                else -> null
            }
        if (selected == null || !selected.available) {
            fail(
                LANGUAGE_UNAVAILABLE,
                SpeechErrorKind.Language,
            )
            return
        }
        recognizer = selected
        selected.defaultTaskHint = SFSpeechRecognitionTaskHintDictation
        // A busy UI must not delay detaching a completed task's audio sink: its callbacks only
        // touch the synchronized capture before hopping to the main queue.
        selected.queue =
            NSOperationQueue().apply {
                name = "dev.nucleusframework.speech.recognition"
                maxConcurrentOperationCount = 1
                qualityOfService = NSQualityOfServiceUserInitiated
            }
        if (!activateAudioSession()) return
        observers +=
            observe(AVAudioSessionInterruptionNotification, null) {
                if (!stopping &&
                    !finished
                ) {
                    fail("Speech recording was interrupted by another audio session.", SpeechErrorKind.Audio)
                }
            }
        val engine = AVAudioEngine()
        this.engine = engine
        observers +=
            observe(AVAudioEngineConfigurationChangeNotification, engine) {
                if (!stopping && !finished) {
                    fail(
                        "The microphone changed or disconnected. Start dictation again to use the new microphone.",
                        SpeechErrorKind.Audio,
                    )
                }
            }
        beginUtterance()
    }

    private fun activateAudioSession(): Boolean {
        val audio = AVAudioSession.sharedInstance()
        previousAudio = AudioConfiguration(audio.category, audio.mode, audio.categoryOptions)
        val failure =
            memScoped {
                val error = alloc<ObjCObjectVar<NSError?>>()
                val configured =
                    audio.setCategory(
                        AVAudioSessionCategoryPlayAndRecord,
                        AVAudioSessionModeMeasurement,
                        speechAudioOptions,
                        error.ptr,
                    ) &&
                        audio.setActive(true, error.ptr)
                if (configured) null else error.value?.localizedDescription ?: "unknown error"
            }
        if (failure != null) {
            fail("Could not activate the microphone: $failure", SpeechErrorKind.Audio)
            return false
        }
        activatedAudioSession = true
        return true
    }

    /**
     * Notification observers wait for their block even when it targets the main queue, and
     * engine teardown waits for the engine's notification queue: always hop back first.
     */
    private fun observe(
        name: String?,
        target: Any?,
        handler: () -> Unit,
    ): NSObjectProtocol =
        NSNotificationCenter.defaultCenter.addObserverForName(name, target, null) { _ -> onMain(handler) }

    @Suppress("CyclomaticComplexMethod", "LongMethod", "ReturnCount")
    private fun beginUtterance(finishing: Boolean = false) {
        if (finished || (stopping && !finishing)) return
        val recognizer = recognizer ?: return
        val engine = engine ?: return
        if (!recognizer.available) {
            // Also drops on a brief network loss for server-backed locales: retryable.
            fail("The system speech recognition service became unavailable.")
            return
        }
        generation++
        val utterance = generation
        endingUtterance = false
        val request =
            SFSpeechAudioBufferRecognitionRequest().apply {
                shouldReportPartialResults = true
                taskHint = SFSpeechRecognitionTaskHintDictation
                if (preferOnDevice && recognizer.supportsOnDeviceRecognition) requiresOnDeviceRecognition = true
                // iOS 16+.
                if (respondsToSelector(NSSelectorFromString("setAddsPunctuation:"))) addsPunctuation = true
            }
        this.request = request
        segments = TranscriptSegments()
        val capture = capture
        capture.prepareRequest(utterance)
        task =
            recognizer.recognitionTaskWithRequest(request) { result, error ->
                // Buffer at once, before the main queue handles the transcript. A late previous
                // callback cannot detach its successor.
                if (result?.final == true || error != null) capture.endRequest(utterance)
                onMain { onRecognition(utterance, result, error) }
            }
        capture.beginRequest(utterance) { request.appendAudioPCMBuffer(it) }
        if (finishing) {
            endRequest()
            scheduleStopTimeout()
            return
        }
        // The existing tap now feeds the new request, audio saved in between included.
        if (tapInstalled) {
            scheduleRollover(utterance)
            return
        }
        val input = engine.inputNode
        val format = input.outputFormatForBus(0u)
        if (format.sampleRate <= 0.0 || format.channelCount == 0u) {
            fail("No working microphone is available.", SpeechErrorKind.Audio)
            return
        }
        val meterFrames = (format.sampleRate / METER_RATE_HZ).toUInt()
        var framesSinceMeter = 0u
        input.installTapOnBus(0u, TAP_BUFFER_FRAMES, format) { buffer, _ ->
            buffer ?: return@installTapOnBus
            if (!capture.append(buffer)) {
                onMain {
                    fail(
                        RESUME_TIMEOUT,
                    )
                }
            }
            framesSinceMeter += buffer.frameLength
            if (framesSinceMeter < meterFrames) return@installTapOnBus
            framesSinceMeter = 0u
            val level = level(buffer) ?: return@installTapOnBus
            onMain { if (!finished && !stopping) send(SpeechEvent.AudioLevel(level)) }
        }
        tapInstalled = true
        engine.prepare()
        val failure =
            memScoped {
                val error = alloc<ObjCObjectVar<NSError?>>()
                if (engine.startAndReturnError(error.ptr)) {
                    null
                } else {
                    error.value?.localizedDescription
                        ?: "unknown error"
                }
            }
        if (failure != null) {
            fail("Could not start the microphone: $failure", SpeechErrorKind.Audio)
            return
        }
        if (!started) {
            started = true
            send(SpeechEvent.Started)
        }
        scheduleRollover(utterance)
    }

    private fun onRecognition(
        utterance: Long,
        result: SFSpeechRecognitionResult?,
        error: NSError?,
    ) {
        if (finished || generation != utterance) return
        if (result != null) {
            val text = result.bestTranscription.formattedString
            if (text.isNotEmpty()) noSpeechRetries = 0
            // iOS 14.5+: a result with metadata closes an on-device segment.
            val closesSegment =
                !result.final &&
                    result.respondsToSelector(NSSelectorFromString("speechRecognitionMetadata")) &&
                    result.speechRecognitionMetadata != null
            segments.result(text, result.final, closesSegment).forEach(::send)
            if (result.final) {
                if (stopping) finishStoppedUtterance() else restartUtterance(RESTART_DELAY_SECONDS)
                return
            }
        }
        if (error == null) return
        val retryDelay = retryDelay(error)
        when {
            // Keep the latest partial if the recognizer ends without a final after endAudio.
            stopping -> finishStoppedUtterance()
            endingUtterance -> {
                commitPartial()
                restartUtterance(RESTART_DELAY_SECONDS)
            }
            retryDelay != null -> {
                commitPartial()
                restartUtterance(retryDelay)
            }
            else -> fail("Speech recognition failed: ${error.localizedDescription}")
        }
    }

    private fun retryDelay(error: NSError): Double? {
        if (error.domain != NO_SPEECH_DOMAIN ||
            error.code != NO_SPEECH_CODE ||
            noSpeechRetries >= MAX_NO_SPEECH_RETRIES
        ) {
            return null
        }
        noSpeechRetries++
        return NO_SPEECH_BASE_DELAY_SECONDS * (1 shl (noSpeechRetries - 1))
    }

    private fun scheduleRollover(utterance: Long) {
        // The tap buffers new audio until the next request starts.
        rollover = after(ROLLOVER_SECONDS) { endUtteranceBeforeLimit(utterance) }
    }

    private fun endUtteranceBeforeLimit(utterance: Long) {
        if (finished || stopping || generation != utterance) return
        endingUtterance = true
        endRequest()
        rollover =
            after(FINALIZATION_TIMEOUT_SECONDS) {
                if (!finished && !stopping && generation == utterance) {
                    commitPartial()
                    restartUtterance(RESTART_DELAY_SECONDS)
                }
            }
    }

    private fun restartUtterance(delay: Double) {
        generation++ // Late callbacks are discarded before the old task is cancelled.
        val next = generation
        rollover?.cancel()
        rollover = null
        endRequest()
        task?.cancel()
        task = null
        pendingRestart =
            after(delay) {
                if (!finished && !stopping && generation == next) {
                    pendingRestart = null
                    beginUtterance()
                }
            }
    }

    private fun endRequest() {
        // Stop appending on the audio thread first, then finalize the request.
        capture.endRequest()
        request?.endAudio()
        request = null
    }

    private fun endCapture(discardPending: Boolean = true) {
        engine?.let { engine ->
            engine.stop()
            if (tapInstalled) {
                engine.inputNode.removeTapOnBus(0u)
                tapInstalled = false
            }
        }
        capture.stop(discardPending)
        endRequest()
    }

    fun stop(cancel: Boolean) {
        if (finished) return
        if (cancel) {
            finished = true
            cleanUp()
            return
        }
        if (stopping) return
        stopping = true
        rollover?.cancel()
        rollover = null
        pendingRestart?.cancel()
        pendingRestart = null
        endCapture(discardPending = false)
        if (task == null) finishStoppedUtterance() else scheduleStopTimeout()
    }

    private fun finishStoppedUtterance() {
        commitPartial()
        if (!capture.hasPendingAudio) {
            finish()
            return
        }
        // The hardware has stopped: transcribe the speech saved while the previous task finalized.
        generation++
        val last = generation
        task?.cancel()
        task = null
        pendingRestart =
            after(RESTART_DELAY_SECONDS) {
                if (!finished && generation == last) {
                    pendingRestart = null
                    beginUtterance(finishing = true)
                    if (task == null && !finished) finish()
                }
            }
    }

    private fun scheduleStopTimeout() {
        val utterance = generation
        // A service can fail to complete after losing its network or microphone.
        after(FINALIZATION_TIMEOUT_SECONDS) {
            if (!finished && generation == utterance) finishStoppedUtterance()
        }
    }

    private fun commitPartial() {
        segments.flush().forEach(::send)
    }

    private fun finish() {
        if (finished) return
        cleanUp()
        send(SpeechEvent.Stopped)
        finished = true
    }

    private fun fail(
        message: String,
        kind: SpeechErrorKind = SpeechErrorKind.Other,
    ) {
        if (finished) return
        cleanUp()
        send(SpeechEvent.Error(kind, message))
        finished = true
    }

    private fun cleanUp() {
        rollover?.cancel()
        rollover = null
        pendingRestart?.cancel()
        pendingRestart = null
        observers.forEach(NSNotificationCenter.defaultCenter::removeObserver)
        observers.clear()
        endCapture()
        task?.cancel()
        task = null
        engine = null
        restoreAudioSession()
        IosSpeechBackend.sessions.remove(id)
    }

    private fun restoreAudioSession() {
        val previous = previousAudio ?: return
        previousAudio = null
        val audio = AVAudioSession.sharedInstance()
        // Another component may have taken the shared session over: only restore it while it
        // still has our recording configuration.
        if (audio.category == AVAudioSessionCategoryPlayAndRecord &&
            audio.mode == AVAudioSessionModeMeasurement &&
            audio.categoryOptions == speechAudioOptions
        ) {
            // With the untouched default category, dictation owns activation: deactivate before
            // restoring soloAmbient so other apps stay audible.
            val untouchedDefault =
                previous.category == AVAudioSessionCategorySoloAmbient &&
                    previous.mode == AVAudioSessionModeDefault &&
                    previous.options == 0uL
            if (activatedAudioSession && untouchedDefault) {
                audio.setActive(false, AVAudioSessionSetActiveOptionNotifyOthersOnDeactivation, null)
            }
            audio.setCategory(previous.category ?: AVAudioSessionCategoryAmbient, previous.mode, previous.options, null)
        }
        activatedAudioSession = false
    }
}

private fun isSupportedLocale(locale: NSLocale): Boolean {
    fun normalized(identifier: String) = identifier.replace('_', '-').lowercase()
    val wanted = normalized(locale.localeIdentifier)
    return SFSpeechRecognizer.supportedLocales().any {
        (it as? NSLocale)?.localeIdentifier?.let(
            ::normalized,
        ) == wanted
    }
}

/** RMS of a float buffer mapped from -60..0 dBFS onto 0..1. */
private fun level(buffer: AVAudioPCMBuffer): Float? {
    val samples = buffer.floatChannelData ?: return null
    val frames = buffer.frameLength.toInt()
    val channels = buffer.format.channelCount.toInt()
    if (frames == 0 || channels == 0) return null
    val interleaved = buffer.format.interleaved
    var energy = 0f
    for (channel in 0 until channels) {
        for (frame in 0 until frames) {
            val sample = if (interleaved) samples[0]!![frame * channels + channel] else samples[channel]!![frame]
            energy += sample * sample
        }
    }
    val rms = sqrt(energy / (frames * channels))
    @Suppress("MagicNumber")
    return min(1f, max(0f, (20f * log10(max(rms, 0.000001f)) + 60f) / 60f))
}
