package dev.nucleusframework.speech

import android.Manifest
import android.app.Activity
import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

private const val MAX_TRANSIENT_RETRIES = 3
private const val RESTART_DELAY_MS = 150L
private const val RETRY_BASE_DELAY_MS = 500L
private const val FINAL_RESULT_TIMEOUT_MS = 3000L

// Android reports RMS in dB, roughly -2..10.
private const val RMS_FULL_SCALE = 10f

/**
 * One dictation session driving the system [SpeechRecognizer], restarted between utterances.
 * A Kotlin port of robius-speech's `NativeSpeech.java`. Everything runs on the main looper.
 */
@Suppress("TooManyFunctions")
internal class AndroidSpeechSession private constructor(
    private val activity: Activity,
    private val id: Long,
    private val options: SpeechRecognitionOptions,
) : Application.ActivityLifecycleCallbacks {
    private var recognizer: SpeechRecognizer? = null
    private var permissionRequest: SpeechPermissionFragment? = null
    private var listening = true
    private var awaitingResult = false
    private var started = false
    private var disposed = false
    private var triedSystemFallback = false
    private var askedForPermission = false
    private var generation = 0
    private var transientFailures = 0
    private var pending = ""
    private val restart = Runnable { listen() }
    private val finalTimeout = Runnable { finish() }

    private fun send(event: SpeechEvent) = SpeechSessions.emit(id, event)

    private fun begin() {
        if (activity.isFinishing || activity.isDestroyed) {
            fail("The application is no longer active.")
            return
        }
        if (activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            // Ask once, then resume from the top. The guard also stops a recognizer that
            // reports the permission missing even after a grant from looping.
            if (askedForPermission) {
                fail(
                    "Allow microphone access in Android settings to use speech input.",
                    SpeechErrorKind.PermissionDenied,
                )
                return
            }
            askedForPermission = true
            permissionRequest =
                SpeechPermissionFragment.request(activity) { outcome ->
                    if (disposed) return@request
                    permissionRequest = null
                    when (outcome) {
                        SpeechPermissionFragment.Outcome.Granted -> begin()
                        SpeechPermissionFragment.Outcome.Cancelled -> finish()
                        SpeechPermissionFragment.Outcome.Denied ->
                            fail("Microphone access is needed for speech input.", SpeechErrorKind.PermissionDenied)
                    }
                }
            return
        }
        try {
            activity.application.registerActivityLifecycleCallbacks(this)
            listen()
        } catch (_: RuntimeException) {
            fail("Unable to start the system speech recognition service.", SpeechErrorKind.Unavailable)
        }
    }

    private fun createRecognizer(): SpeechRecognizer? {
        if (options.preferOnDevice && !triedSystemFallback && isOnDeviceRecognitionAvailable(activity)) {
            try {
                return SpeechRecognizer.createOnDeviceSpeechRecognizer(activity)
            } catch (_: RuntimeException) {
                // Some vendor services advertise on-device support without providing it.
            }
        }
        return if (SpeechRecognizer.isRecognitionAvailable(
                activity,
            )
        ) {
            SpeechRecognizer.createSpeechRecognizer(activity)
        } else {
            null
        }
    }

    private fun listen() {
        if (disposed || !listening) return
        pending = ""
        val intent =
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                options.locale?.takeIf(String::isNotEmpty)?.let { putExtra(RecognizerIntent.EXTRA_LANGUAGE, it) }
                // Only a preference on older devices: the installed service may ignore it.
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, options.preferOnDevice && !triedSystemFallback)
            }
        try {
            val current = recognizer ?: createRecognizer()
            recognizer = current
            if (current == null) {
                fail("No speech recognition service is installed on this device.", SpeechErrorKind.Unavailable)
                return
            }
            installListener(current)
            awaitingResult = true
            current.startListening(intent)
        } catch (_: RuntimeException) {
            fail("Unable to start microphone recording for speech input.", SpeechErrorKind.Audio)
        }
    }

    /**
     * Each attempt gets a new listener generation: destroyed services can still have Binder
     * callbacks queued, which must not commit an old hypothesis into a successor.
     */
    private fun installListener(target: SpeechRecognizer) {
        val installed = ++generation
        target.setRecognitionListener(
            object : RecognitionListener {
                private val current get() = !disposed && installed == generation

                override fun onReadyForSpeech(params: Bundle?) {
                    if (current) onReady()
                }

                override fun onBeginningOfSpeech() = Unit

                override fun onRmsChanged(rmsdB: Float) {
                    if (current && listening) {
                        send(
                            SpeechEvent.AudioLevel(
                                if (rmsdB.isNaN()) 0f else (rmsdB / RMS_FULL_SCALE).coerceIn(0f, 1f),
                            ),
                        )
                    }
                }

                override fun onBufferReceived(buffer: ByteArray?) = Unit

                override fun onEndOfSpeech() {
                    if (current) send(SpeechEvent.AudioLevel(0f))
                }

                override fun onError(error: Int) {
                    if (current) onRecognitionError(error)
                }

                override fun onResults(results: Bundle?) {
                    if (current) onFinalResults(results)
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    if (current) onPartial(partialResults)
                }

                override fun onEvent(
                    eventType: Int,
                    params: Bundle?,
                ) = Unit
            },
        )
    }

    private fun onReady() {
        if (!listening || !awaitingResult || started) return
        started = true
        send(SpeechEvent.Started)
    }

    private fun onPartial(results: Bundle?) {
        if (!awaitingResult || results == null) return
        val text = transcript(results)
        if (text.isNotEmpty() && text != pending) {
            pending = text
            send(SpeechEvent.Transcript(text, isFinal = false))
        }
    }

    private fun onFinalResults(results: Bundle?) {
        if (!awaitingResult) return
        awaitingResult = false
        transientFailures = 0
        val text = results?.let(::transcript).orEmpty()
        // A final that only cuts words off the end of the last partial keeps them.
        if (text.isNotEmpty() && !isShortenedRevision(pending, text)) pending = text
        commitPending()
        nextUtterance()
    }

    @Suppress("CyclomaticComplexMethod", "ReturnCount")
    private fun onRecognitionError(error: Int) {
        if (!awaitingResult) return
        awaitingResult = false
        if (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
            transientFailures = 0
            commitPending()
            nextUtterance()
            return
        }
        if (!listening) {
            finish()
            return
        }
        if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY || error == SpeechRecognizer.ERROR_CLIENT) {
            retryTransientError(error)
            return
        }
        val languageMissing =
            error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED ||
                error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE
        // On-device recognition may exist without a model for the language: let the regular
        // service handle it when there is one.
        if (languageMissing &&
            options.preferOnDevice &&
            !triedSystemFallback &&
            SpeechRecognizer.isRecognitionAvailable(activity)
        ) {
            triedSystemFallback = true
            try {
                ++generation
                recognizer?.destroy()
                recognizer = SpeechRecognizer.createSpeechRecognizer(activity)
                commitPending()
                nextUtterance()
                return
            } catch (_: RuntimeException) {
                // Fall through to the language error.
            }
        }
        when {
            error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
                fail(
                    "Allow microphone access in Android settings to use speech input.",
                    SpeechErrorKind.PermissionDenied,
                )
            error == SpeechRecognizer.ERROR_AUDIO ->
                fail("The microphone is unavailable. Check whether another app is using it.", SpeechErrorKind.Audio)
            error == SpeechRecognizer.ERROR_NETWORK || error == SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
                fail("The system speech service needs a network connection. Check your connection and try again.")
            languageMissing ->
                fail(
                    "The system speech service does not have recognition support for this language.",
                    SpeechErrorKind.Language,
                )
            else -> fail("The system speech service stopped (error $error). Try again.")
        }
    }

    private fun retryTransientError(error: Int) {
        commitPending()
        if (transientFailures >= MAX_TRANSIENT_RETRIES) {
            fail("The system speech service is still unavailable. Try again in a moment.")
            return
        }
        ++generation
        // A client error can mean its Binder connection died: recreate the client.
        if (error == SpeechRecognizer.ERROR_CLIENT) {
            release(recognizer)
            recognizer = null
        }
        send(SpeechEvent.AudioLevel(0f))
        main.removeCallbacks(restart)
        // Not reset on onReadyForSpeech: a broken service can report ready and fail repeatedly.
        main.postDelayed(restart, RETRY_BASE_DELAY_MS shl transientFailures++)
    }

    private fun stop(cancel: Boolean) {
        listening = false
        main.removeCallbacks(restart)
        when {
            cancel -> dispose()
            !awaitingResult -> finish()
            else ->
                try {
                    recognizer?.stopListening()
                    main.postDelayed(finalTimeout, FINAL_RESULT_TIMEOUT_MS)
                } catch (_: RuntimeException) {
                    finish()
                }
        }
    }

    private fun commitPending() {
        if (pending.isNotEmpty()) {
            send(SpeechEvent.Transcript(pending, isFinal = true))
            pending = ""
        }
    }

    private fun nextUtterance() {
        awaitingResult = false
        send(SpeechEvent.AudioLevel(0f))
        if (listening) main.postDelayed(restart, RESTART_DELAY_MS) else finish()
    }

    private fun finish() {
        if (disposed) return
        commitPending()
        dispose()
        send(SpeechEvent.Stopped)
    }

    private fun fail(
        message: String,
        kind: SpeechErrorKind = SpeechErrorKind.Other,
    ) {
        if (disposed) return
        // Keep the words already shown before reporting the error.
        commitPending()
        dispose()
        send(SpeechEvent.Error(kind, message))
    }

    private fun dispose() {
        if (disposed) return
        disposed = true
        listening = false
        main.removeCallbacks(restart)
        main.removeCallbacks(finalTimeout)
        sessions.remove(id)
        permissionRequest?.cancel()
        permissionRequest = null
        activity.application.unregisterActivityLifecycleCallbacks(this)
        release(recognizer)
        recognizer = null
    }

    override fun onActivityPaused(activity: Activity) {
        if (activity === this.activity) finish()
    }

    override fun onActivityDestroyed(activity: Activity) {
        if (activity === this.activity) finish()
    }

    override fun onActivityCreated(
        activity: Activity,
        savedInstanceState: Bundle?,
    ): Unit = Unit

    override fun onActivityStarted(activity: Activity): Unit = Unit

    override fun onActivityResumed(activity: Activity): Unit = Unit

    override fun onActivityStopped(activity: Activity): Unit = Unit

    override fun onActivitySaveInstanceState(
        activity: Activity,
        outState: Bundle,
    ): Unit = Unit

    companion object {
        private val main = Handler(Looper.getMainLooper())

        // Main looper only.
        private val sessions = HashMap<Long, AndroidSpeechSession>()

        fun start(
            activity: Activity,
            id: Long,
            options: SpeechRecognitionOptions,
        ) {
            main.post {
                val session = AndroidSpeechSession(activity, id, options)
                sessions[id] = session
                session.begin()
            }
        }

        fun stop(
            id: Long,
            cancel: Boolean,
        ) {
            main.post { sessions[id]?.stop(cancel) }
        }

        private fun transcript(results: Bundle): String =
            results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()

        private fun release(recognizer: SpeechRecognizer?) {
            recognizer ?: return
            try {
                recognizer.cancel()
            } catch (_: RuntimeException) {
            }
            try {
                recognizer.destroy()
            } catch (_: RuntimeException) {
            }
        }
    }
}
