// macOS and iOS dictation, using SFSpeechRecognizer plus AVAudioEngine.
//
// This side asks for both permissions, owns the microphone, and restarts the
// recognizer between utterances. Rust only sees the events sent to the callback
// below. Note that session state lives on the main queue; only the audio tap runs
// elsewhere, and it hands its buffers over through a synchronized capture sink.

import Foundation
import AVFoundation
import Speech

@_silgen_name("robius_speech_event")
private func nativeEvent(_ id: UInt64, _ kind: Int32, _ text: UnsafePointer<CChar>?, _ level: Float)

// Session/recognizer state lives on the main queue. Audio taps use a synchronized
// capture sink and marshal meter updates and errors back to that queue.
private var sessions: [UInt64: NativeSpeech] = [:]

// Error kinds, matching `codes` in lib.rs. Not private: the regression test
// asserts against these rather than repeating the numbers.
let errOther: Int32 = 5
let errPermission: Int32 = 6
let errUnavailable: Int32 = 7
let errLanguage: Int32 = 8
let errAudio: Int32 = 9

#if os(iOS)
// The HFP spelling was introduced by the iOS 26 SDK. This older spelling has
// the identical value and also compiles with older supported Xcode releases.
private let speechAudioOptions: AVAudioSession.CategoryOptions = [.defaultToSpeaker, .allowBluetooth, .mixWithOthers]
#endif

// NotificationCenter waits for an observer even when it targets OperationQueue.main.
// Audio-engine notifications originate on an internal queue that engine teardown
// also waits for. Return to that queue before touching the engine on the main queue.
func observeAudioNotification(_ name: Notification.Name, object: Any?, handler: @escaping () -> Void) -> NSObjectProtocol {
    NotificationCenter.default.addObserver(forName: name, object: object, queue: nil) { _ in
        DispatchQueue.main.async { handler() }
    }
}

struct SpeechRetryPolicy {
    private(set) var consecutiveNoSpeech = 0

    mutating func madeProgress() { consecutiveNoSpeech = 0 }

    mutating func retryDelay(after error: NSError) -> TimeInterval? {
        // Apple's documented no-speech error. Code 203 is a generic failure,
        // not a documented timeout; authorization/network failures stay visible.
        // https://developer.apple.com/documentation/speech/sfspeechrecognitiontask/error
        guard error.domain == "kAFAssistantErrorDomain", error.code == 1110, consecutiveNoSpeech < 3 else { return nil }
        consecutiveNoSpeech += 1
        return 0.25 * Double(1 << (consecutiveNoSpeech - 1))
    }
}

/// Turns one recognition task's results into transcripts that never drop words already sent.
///
/// After a pause, on-device recognition closes a segment with one result that has metadata,
/// then restarts `bestTranscription` from the new words only, without ever setting `isFinal`.
/// Its final result after `endAudio` can hold only the last segment, or nothing at all.
struct SegmentTracker {
    /// Words sent as a partial but not yet committed.
    private(set) var open = ""
    /// The last text committed, and the whole transcription at that point.
    private var committed = ""
    private var closedTranscription = ""
    /// Whether this task restarts its transcription after each segment, rather than growing it.
    private var restarts = false

    /// Returns the transcripts to send (kind 1 partial, kind 2 final) for one result.
    mutating func result(_ transcription: String, isFinal: Bool, closesSegment: Bool) -> [(Int32, String)] {
        var text = transcription.trimmingCharacters(in: .whitespacesAndNewlines)
        // A transcription that grows instead of restarting still holds what was committed.
        if !closedTranscription.isEmpty && !restarts {
            if let rest = looseRemainder(of: text, after: closedTranscription) {
                text = rest
            } else if !text.isEmpty {
                restarts = true
            }
        }
        var out: [(Int32, String)] = []
        if isFinal {
            // An empty or cut-short final keeps what was shown, and a repeat of the
            // segment just committed isn't committed again.
            if text.isEmpty || (open.isEmpty && looselyEqual(text, committed)) || isShortenedRevision(of: open, text) {
                return flush()
            }
            if startsOver(from: open, to: text) { out += flush() }
            open = ""
            return out + commit(text)
        }
        guard !text.isEmpty, !(closesSegment && open.isEmpty && looselyEqual(text, committed)) else { return [] }
        if startsOver(from: open, to: text) { out += flush() }
        if closesSegment {
            open = ""
            closedTranscription = transcription
            return out + commit(text)
        }
        open = text
        out.append((1, text))
        return out
    }

    /// Commits the words shown but not yet committed, e.g. when a task ends without a final.
    mutating func flush() -> [(Int32, String)] {
        defer { open = "" }
        return commit(open)
    }

    private mutating func commit(_ text: String) -> [(Int32, String)] {
        guard !text.isEmpty else { return [] }
        committed = text
        return [(2, text)]
    }
}

/// Words compared loosely, ignoring case and punctuation, as `dictation.rs` does.
func looseWords(_ text: String) -> [String] {
    text.split(whereSeparator: { $0.isWhitespace })
        .map { $0.trimmingCharacters(in: CharacterSet.alphanumerics.inverted).lowercased() }
        .filter { !$0.isEmpty }
}

func looselyEqual(_ a: String, _ b: String) -> Bool {
    looseWords(a) == looseWords(b)
}

/// Whether `new` starts over rather than revising `old`, as after a pause without metadata:
/// it drops most words of a long `old`, rather than reformatting a few (e.g. "300 and" to "340").
func startsOver(from old: String, to new: String) -> Bool {
    let (old, new) = (looseWords(old), looseWords(new))
    let kept = old.filter(new.contains).count
    return old.count >= 6 && !new.isEmpty && new.count * 2 < old.count && kept * 4 < old.count
}

/// Whether `new` is `old` with words cut off the end.
func isShortenedRevision(of old: String, _ new: String) -> Bool {
    let (old, new) = (looseWords(old), looseWords(new))
    return !new.isEmpty && new.count < old.count && Array(old.prefix(new.count)) == new
}

/// The rest of `text` after its first words, if they loosely match all of `prefix`'s words.
func looseRemainder(of text: String, after prefix: String) -> String? {
    var expected = looseWords(prefix)[...]
    var rest = Substring(text)
    while let word = expected.first {
        rest = rest.drop(while: { $0.isWhitespace })
        guard !rest.isEmpty else { return nil }
        let token = rest.prefix(while: { !$0.isWhitespace })
        rest = rest.dropFirst(token.count)
        let loose = looseWords(String(token))
        if loose.isEmpty { continue }
        guard loose == [word] else { return nil }
        expected = expected.dropFirst()
    }
    return rest.trimmingCharacters(in: .whitespacesAndNewlines)
}

// The engine keeps recording while a recognizer finalizes an utterance. Only
// that short interval needs copies; otherwise buffers go straight to the request.
// No engine operations or UI callbacks run while this lock is held.
final class SpeechAudioCapture {
    private let lock = NSLock()
    private var appendToRequest: ((AVAudioPCMBuffer) -> Void)?
    private var requestGeneration: UInt64?
    private var pending: [AVAudioPCMBuffer] = []
    private var pendingDuration: TimeInterval = 0
    private var active = false
    private var overflowed = false

    func prepareRequest(_ generation: UInt64) {
        lock.lock()
        active = true
        requestGeneration = generation
        appendToRequest = nil
        lock.unlock()
    }

    func beginRequest(generation: UInt64? = nil, _ append: @escaping (AVAudioPCMBuffer) -> Void) {
        lock.lock()
        defer { lock.unlock() }
        // A task may finish immediately, before recognitionTask() even returns.
        // Do not bind its request after that callback has already detached it.
        if let generation = generation, requestGeneration != generation { return }
        active = true
        appendToRequest = append
        for buffer in pending { append(buffer) }
        pending.removeAll(keepingCapacity: true)
        pendingDuration = 0
    }

    func endRequest(ifCurrent generation: UInt64? = nil) {
        lock.lock()
        defer { lock.unlock() }
        if let generation = generation, requestGeneration != generation { return }
        requestGeneration = nil
        appendToRequest = nil
    }

    // False reports one overflow/allocation error. Further buffers are discarded
    // until the main queue handles that terminal error and stops capture.
    func append(_ buffer: AVAudioPCMBuffer) -> Bool {
        lock.lock()
        defer { lock.unlock() }
        guard active, !overflowed else { return true }
        if let append = appendToRequest { append(buffer); return true }
        let duration = Double(buffer.frameLength) / buffer.format.sampleRate
        guard duration.isFinite, pendingDuration + duration <= 4,
            let copy = AVAudioPCMBuffer(pcmFormat: buffer.format, frameCapacity: buffer.frameLength)
        else {
            overflowed = true
            pending.removeAll()
            pendingDuration = 0
            return false
        }
        copy.frameLength = buffer.frameLength
        let source = UnsafeMutableAudioBufferListPointer(UnsafeMutablePointer(mutating: buffer.audioBufferList))
        let destination = UnsafeMutableAudioBufferListPointer(copy.mutableAudioBufferList)
        guard source.count == destination.count,
            source.indices.allSatisfy({ source[$0].mDataByteSize <= destination[$0].mDataByteSize })
        else {
            overflowed = true
            pending.removeAll()
            pendingDuration = 0
            return false
        }
        for index in source.indices {
            if let from = source[index].mData, let to = destination[index].mData {
                memcpy(to, from, Int(source[index].mDataByteSize))
            }
        }
        pending.append(copy)
        pendingDuration += duration
        return true
    }

    var hasPendingAudio: Bool {
        lock.lock()
        defer { lock.unlock() }
        return !pending.isEmpty
    }

    func stop(discardPending: Bool = true) {
        lock.lock()
        active = false
        requestGeneration = nil
        appendToRequest = nil
        if discardPending {
            pending.removeAll()
            pendingDuration = 0
        }
        lock.unlock()
    }
}

final class NativeSpeech {
    let id: UInt64
    let locale: Locale?
    let preferOnDevice: Bool
    // Do not initialize audio hardware before the privacy checks and permissions.
    var engine: AVAudioEngine?
    let capture = SpeechAudioCapture()
    var recognizer: SFSpeechRecognizer?
    var request: SFSpeechAudioBufferRecognitionRequest?
    var task: SFSpeechRecognitionTask?
    var stopping = false
    var finished = false
    var tapInstalled = false
    var started = false
    var segments = SegmentTracker()
    var generation: UInt64 = 0
    var retryPolicy = SpeechRetryPolicy()
    var endingUtterance = false
    var rollover: DispatchWorkItem?
    var pendingRestart: DispatchWorkItem?
    var observers: [NSObjectProtocol] = []
    #if os(iOS)
    var previousAudioConfiguration: (AVAudioSession.Category, AVAudioSession.Mode, AVAudioSession.CategoryOptions)?
    var activatedAudioSession = false
    #endif

    init(id: UInt64, locale: String, preferOnDevice: Bool) {
        self.id = id
        self.locale = locale.isEmpty ? nil : Locale(identifier: locale)
        self.preferOnDevice = preferOnDevice
    }

    func send(_ kind: Int32, _ text: String = "", _ level: Float = 0) {
        guard !finished else { return }
        text.withCString { nativeEvent(id, kind, $0, level) }
    }

    func authorize() {
        let usageKeys = ["NSMicrophoneUsageDescription", "NSSpeechRecognitionUsageDescription"]
        #if os(macOS)
        // An embedded __info_plist satisfies Bundle.main, but TCC can still
        // attribute an unbundled process to its launching terminal or editor.
        // Those apps lack our speech description, and TCC terminates the process.
        let bundle = Bundle.main.bundleURL
        guard bundle.pathExtension == "app",
            let data = try? Data(contentsOf: bundle.appendingPathComponent("Contents/Info.plist")),
            let plist = (try? PropertyListSerialization.propertyList(from: data, format: nil)) as? [String: Any],
            usageKeys.allSatisfy({ (plist[$0] as? String)?.isEmpty == false })
        else {
            fail("Launch the application from its .app bundle to use speech input.", errPermission)
            return
        }
        #endif
        // Missing privacy keys cause a process termination in Apple's APIs.
        // Report an actionable error before invoking either permission prompt.
        for key in usageKeys {
            guard let reason = Bundle.main.object(forInfoDictionaryKey: key) as? String, !reason.isEmpty else {
                fail("This app is missing its \(key) privacy description.", errPermission)
                return
            }
        }
        #if ROBIUS_SPEECH_TESTS
        // Native lifecycle regressions must never request real microphone or
        // speech permission, including when testing an invalid launch context.
        preconditionFailure("Unexpected permission request in native speech tests")
        #else
        SFSpeechRecognizer.requestAuthorization { [weak self] status in
            DispatchQueue.main.async {
                guard let self = self, !self.finished, !self.stopping else { return }
                guard status == .authorized else {
                    self.fail("Speech recognition permission was denied. Allow speech recognition for this app in system privacy settings.", errPermission)
                    return
                }
                AVCaptureDevice.requestAccess(for: .audio) { [weak self] granted in
                    DispatchQueue.main.async {
                        guard let self = self, !self.finished, !self.stopping else { return }
                        guard granted else {
                            self.fail("Microphone permission was denied. Allow microphone access for this app in system privacy settings.", errPermission)
                            return
                        }
                        self.start()
                    }
                }
            }
        }
        #endif
    }

    func start() {
        // Use the native default-language selection rather than a region-based
        // Locale.current such as en_NL. Explicit locales retain Apple's built-in
        // fallback to the keyboard's dictation language if unsupported.
        let selectedRecognizer = locale.map { SFSpeechRecognizer(locale: $0) } ?? SFSpeechRecognizer()
        guard let recognizer = selectedRecognizer, recognizer.isAvailable else {
            fail("Speech recognition is unavailable for the current language. Check the system speech settings and network connection.", errLanguage)
            return
        }
        self.recognizer = recognizer
        recognizer.defaultTaskHint = .dictation
        // A busy UI must not delay detaching a completed task's audio sink.
        // Only the synchronized sink is touched here; session state stays on main.
        let callbacks = OperationQueue()
        callbacks.name = "org.robius.speech.recognition"
        callbacks.maxConcurrentOperationCount = 1
        callbacks.qualityOfService = .userInitiated
        recognizer.queue = callbacks
        #if os(iOS)
        do {
            let audio = AVAudioSession.sharedInstance()
            previousAudioConfiguration = (audio.category, audio.mode, audio.categoryOptions)
            try audio.setCategory(.playAndRecord, mode: .measurement, options: speechAudioOptions)
            try audio.setActive(true)
            activatedAudioSession = true
        } catch {
            fail("Could not activate the microphone: \(error.localizedDescription)", errAudio)
            return
        }
        observers.append(observeAudioNotification(AVAudioSession.interruptionNotification, object: nil) { [weak self] in
            guard let self = self, !self.stopping, !self.finished else { return }
            self.fail("Speech recording was interrupted by another audio session.", errAudio)
        })
        #endif
        let engine = AVAudioEngine()
        self.engine = engine
        observers.append(observeAudioNotification(.AVAudioEngineConfigurationChange, object: engine) { [weak self] in
            guard let self = self, !self.stopping, !self.finished else { return }
            self.fail("The microphone changed or disconnected. Start dictation again to use the new microphone.", errAudio)
        })
        beginUtterance()
    }

    func beginUtterance(finishing: Bool = false) {
        guard !finished, (!stopping || finishing), let recognizer = recognizer, let engine = engine else { return }
        guard recognizer.isAvailable else {
            // isAvailable also drops on a brief network loss for server-backed
            // locales, so this is retryable rather than a missing recognizer.
            fail("The system speech recognition service became unavailable.")
            return
        }
        generation &+= 1
        let utterance = generation
        endingUtterance = false
        let request = SFSpeechAudioBufferRecognitionRequest()
        request.shouldReportPartialResults = true
        request.taskHint = .dictation
        if preferOnDevice && recognizer.supportsOnDeviceRecognition {
            request.requiresOnDeviceRecognition = true
        }
        if #available(macOS 13.0, iOS 16.0, *) { request.addsPunctuation = true }
        self.request = request
        segments = SegmentTracker()
        let capture = self.capture
        capture.prepareRequest(utterance)
        task = recognizer.recognitionTask(with: request) { [weak self] result, error in
            if result?.isFinal == true || error != nil {
                // Start buffering immediately, before the main queue handles the
                // transcript. A late previous callback cannot detach its successor.
                capture.endRequest(ifCurrent: utterance)
            }
            DispatchQueue.main.async {
                guard let self = self, !self.finished, self.generation == utterance else { return }
                if let result = result {
                    let text = result.bestTranscription.formattedString
                    if !text.isEmpty { self.retryPolicy.madeProgress() }
                    var closesSegment = false
                    if #available(iOS 14.0, *) {
                        closesSegment = !result.isFinal && result.speechRecognitionMetadata != nil
                    }
                    for (kind, text) in self.segments.result(text, isFinal: result.isFinal, closesSegment: closesSegment) {
                        self.send(kind, text)
                    }
                    if result.isFinal {
                        if self.stopping { self.finishStoppedUtterance() }
                        else { self.restartUtterance(after: 0.15) }
                        return
                    }
                }
                if let error = error {
                    if self.stopping {
                        // Preserve the most recent partial if a system recognizer
                        // ends without delivering a final result after endAudio.
                        self.finishStoppedUtterance()
                    } else if self.endingUtterance {
                        self.commitPartial()
                        self.restartUtterance(after: 0.15)
                    } else if let delay = self.retryPolicy.retryDelay(after: error as NSError) {
                        self.commitPartial()
                        self.restartUtterance(after: delay)
                    } else {
                        self.fail("Speech recognition failed: \(error.localizedDescription)")
                    }
                }
            }
        }
        capture.beginRequest(generation: utterance) { request.append($0) }
        if finishing { endRequest(); scheduleStopTimeout(); return }
        // The existing tap now targets the new request, including PCM saved
        // during finalization/backoff. Keeping the engine running avoids gaps.
        if tapInstalled { scheduleRollover(utterance); return }
        let input = engine.inputNode
        let format = input.outputFormat(forBus: 0)
        // Without any input device the output side still reports a synthesized format, but the
        // hardware side is 0 Hz / 0 ch and installTap raises an NSException.
        let hardware = input.inputFormat(forBus: 0)
        guard format.sampleRate > 0 && format.channelCount > 0 && hardware.sampleRate > 0 && hardware.channelCount > 0 else {
            fail("No working microphone is available.", errAudio)
            return
        }
        var meterFrames: AVAudioFrameCount = 0
        input.installTap(onBus: 0, bufferSize: 1024, format: format) { [weak self] buffer, _ in
            if !capture.append(buffer) {
                DispatchQueue.main.async { [weak self] in
                    self?.fail("Speech recognition took too long to resume. Your draft has been kept; please start dictation again.")
                }
            }
            meterFrames += buffer.frameLength
            guard meterFrames >= AVAudioFrameCount(format.sampleRate / 30) else { return }
            meterFrames = 0
            guard let samples = buffer.floatChannelData, buffer.frameLength > 0 else { return }
            var energy: Float = 0
            let channels = Int(buffer.format.channelCount)
            let count = Int(buffer.frameLength)
            for channel in 0..<channels {
                for frame in 0..<count {
                    let sample = buffer.format.isInterleaved ? samples[0][frame * channels + channel] : samples[channel][frame]
                    energy += sample * sample
                }
            }
            let rms = sqrt(energy / Float(count * channels))
            let level = min(1, max(0, (20 * log10(max(rms, 0.000001)) + 60) / 60))
            DispatchQueue.main.async {
                guard let self = self, !self.finished, !self.stopping else { return }
                self.send(3, "", level)
            }
        }
        tapInstalled = true
        do {
            engine.prepare()
            try engine.start()
            if !started { started = true; send(0) }
            scheduleRollover(utterance)
        } catch {
            fail("Could not start the microphone: \(error.localizedDescription)", errAudio)
        }
    }

    func scheduleRollover(_ utterance: UInt64) {
        // Apple documents a one-minute task limit. Ask for a final result before
        // that limit; the tap buffers new audio until the next request starts.
        let rollover = DispatchWorkItem { [weak self] in
            self?.endUtteranceBeforeLimit(utterance)
        }
        self.rollover = rollover
        DispatchQueue.main.asyncAfter(deadline: .now() + 55, execute: rollover)
    }

    func endUtteranceBeforeLimit(_ utterance: UInt64, finalizationTimeout: TimeInterval = 3) {
        guard !finished, !stopping, generation == utterance else { return }
        endingUtterance = true
        endRequest()
        let deadline = DispatchWorkItem { [weak self] in
            guard let self = self, !self.finished, !self.stopping, self.generation == utterance else { return }
            self.commitPartial()
            self.restartUtterance(after: 0.15)
        }
        rollover = deadline
        DispatchQueue.main.asyncAfter(deadline: .now() + finalizationTimeout, execute: deadline)
    }

    func restartUtterance(after delay: TimeInterval) {
        generation &+= 1 // Discard late callbacks before cancelling the old task.
        let next = generation
        rollover?.cancel()
        rollover = nil
        endRequest()
        task?.cancel()
        task = nil
        let restart = DispatchWorkItem { [weak self] in
            guard let self = self, !self.finished, !self.stopping, self.generation == next else { return }
            self.pendingRestart = nil
            self.beginUtterance()
        }
        pendingRestart = restart
        DispatchQueue.main.asyncAfter(deadline: .now() + delay, execute: restart)
    }

    func endRequest() {
        // First stop appending on the audio thread, then finalize the old request.
        capture.endRequest()
        request?.endAudio()
        request = nil
    }

    func endCapture(discardPending: Bool = true) {
        if let engine = engine {
            engine.stop()
            if tapInstalled {
                engine.inputNode.removeTap(onBus: 0)
                tapInstalled = false
            }
        }
        capture.stop(discardPending: discardPending)
        endRequest()
    }

    func stop(cancel: Bool) {
        guard !finished else { return }
        if cancel {
            finished = true
            cleanUp()
            return
        }
        guard !stopping else { return }
        stopping = true
        rollover?.cancel()
        rollover = nil
        pendingRestart?.cancel()
        pendingRestart = nil
        endCapture(discardPending: false)
        if task == nil {
            finishStoppedUtterance()
        } else {
            scheduleStopTimeout()
        }
    }

    func finishStoppedUtterance() {
        commitPartial()
        if capture.hasPendingAudio {
            // Hardware has stopped. After the previous task's final result,
            // transcribe speech saved during its finalization/backoff.
            generation &+= 1
            let last = generation
            task?.cancel()
            task = nil
            let finishBuffered = DispatchWorkItem { [weak self] in
                guard let self = self, !self.finished, self.generation == last else { return }
                self.pendingRestart = nil
                self.beginUtterance(finishing: true)
                if self.task == nil && !self.finished { self.finish() }
            }
            pendingRestart = finishBuffered
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.15, execute: finishBuffered)
        } else {
            finish()
        }
    }

    func scheduleStopTimeout() {
        let utterance = generation
        // A native service can fail to complete after losing its network or
        // microphone. Bound finalization without leaving an active session stuck.
        DispatchQueue.main.asyncAfter(deadline: .now() + 3) { [weak self] in
            guard let self = self, !self.finished, self.generation == utterance else { return }
            self.finishStoppedUtterance()
        }
    }

    func commitPartial() {
        for (kind, text) in segments.flush() { send(kind, text) }
    }

    func finish() {
        guard !finished else { return }
        cleanUp()
        send(4)
        finished = true
    }

    func fail(_ message: String, _ kind: Int32 = errOther) {
        guard !finished else { return }
        cleanUp()
        send(kind, message)
        finished = true
    }

    func cleanUp() {
        rollover?.cancel()
        rollover = nil
        pendingRestart?.cancel()
        pendingRestart = nil
        for observer in observers { NotificationCenter.default.removeObserver(observer) }
        observers.removeAll()
        endCapture()
        task?.cancel()
        task = nil
        engine = nil
        #if os(iOS)
        if let previous = previousAudioConfiguration {
            let audio = AVAudioSession.sharedInstance()
            // Another app component may have taken over the shared session.
            // Restore only while it still has our recording configuration.
            if audio.category == .playAndRecord && audio.mode == .measurement && audio.categoryOptions == speechAudioOptions {
                // Playback components configure their own playback/voice categories.
                // With the untouched default category, dictation owns activation:
                // deactivate before restoring soloAmbient so other apps stay audible.
                if activatedAudioSession && previous.0 == .soloAmbient && previous.1 == .default && previous.2.isEmpty {
                    try? audio.setActive(false, options: .notifyOthersOnDeactivation)
                }
                try? audio.setCategory(previous.0, mode: previous.1, options: previous.2)
            }
            previousAudioConfiguration = nil
            activatedAudioSession = false
        }
        #endif
        sessions.removeValue(forKey: id)
    }
}

@_cdecl("robius_speech_start")
public func startNativeSpeech(_ id: UInt64, _ locale: UnsafePointer<CChar>, _ preferOnDevice: Bool) {
    let locale = String(cString: locale)
    DispatchQueue.main.async {
        let session = NativeSpeech(id: id, locale: locale, preferOnDevice: preferOnDevice)
        sessions[id] = session
        session.authorize()
    }
}

@_cdecl("robius_speech_stop")
public func stopNativeSpeech(_ id: UInt64, _ cancel: Bool) {
    DispatchQueue.main.async { sessions[id]?.stop(cancel: cancel) }
}
