package dev.nucleusframework.speech

/**
 * Native streaming speech-to-text.
 *
 * | Platform | Service |
 * | --- | --- |
 * | Windows | SAPI dictation, on-device (through `robius-speech`) |
 * | macOS | SFSpeechRecognizer + AVAudioEngine (through `robius-speech`), macOS 11+ |
 * | Linux | none: [isSupported] is `false` |
 * | Android | `android.speech.SpeechRecognizer`, API 26+ |
 * | iOS | SFSpeechRecognizer + AVAudioEngine |
 *
 * ```kotlin
 * val session = SpeechRecognition.start { event ->
 *     when (event) {
 *         is SpeechEvent.Transcript -> println("${event.text} (final: ${event.isFinal})")
 *         is SpeechEvent.Error -> println("${event.message} (${event.kind})")
 *         else -> Unit
 *     }
 * }
 * // Later: let the last words arrive, then Stopped.
 * session.stop()
 * ```
 *
 * Speech always stays plain text: saying "enter" types the word "enter".
 */
public object SpeechRecognition {
    private val backend: SpeechBackend by lazy { platformSpeechBackend() }

    /**
     * Whether this platform has a native recognizer at all. Cheap and permission-free, so a
     * dictation button can be hidden rather than fail when pressed.
     */
    public val isSupported: Boolean
        get() = backend.isSupported

    /** Short, stable name of the recognizer in use (e.g. `"windows-sapi"`), for logs and bug reports. */
    public val engineName: String
        get() = backend.engineName

    /**
     * Starts dictating, asking for the microphone (and on Apple, speech recognition) permission
     * when needed. Returns at once: [SpeechEvent.Started] says when the microphone records, a
     * refusal arrives as a [SpeechEvent.Error] of kind [SpeechErrorKind.PermissionDenied].
     *
     * [listener] runs on a platform thread (the main thread on Android and Apple, a worker
     * thread on Windows); forward to the UI thread as needed. Only one session runs at a time.
     *
     * @throws SpeechException when the session cannot start, e.g. [SpeechErrorKind.Busy]
     */
    public fun start(
        options: SpeechRecognitionOptions = SpeechRecognitionOptions(),
        listener: (SpeechEvent) -> Unit,
    ): SpeechSession {
        startFailure(options)?.let { throw it }
        val id = SpeechSessions.nextId()
        if (!SpeechSessions.register(id, listener)) {
            throw SpeechException(SpeechErrorKind.Busy, "Another speech recording is already active.")
        }
        var started = false
        try {
            backend.start(id, options)
            started = true
        } finally {
            if (!started) SpeechSessions.unregister(id)
        }
        return SpeechSession(id, backend)
    }

    private fun startFailure(options: SpeechRecognitionOptions): SpeechException? =
        when {
            !backend.isSupported ->
                SpeechException(
                    SpeechErrorKind.Unavailable,
                    "Native speech recognition is not available on this platform.",
                )
            options.locale?.contains('\u0000') == true ->
                SpeechException(SpeechErrorKind.Language, "The speech recognition language is invalid.")
            else -> null
        }

    /** Cancels every active session, e.g. when the app is suspended or quits. */
    public fun cancelAll() {
        for (id in SpeechSessions.unregisterAll()) backend.stop(id, cancel = true)
    }
}

/**
 * A running recognition, owning the microphone until it ends. Closing it cancels it.
 *
 * An in-flight listener call may still complete after [cancel] returns.
 */
public class SpeechSession internal constructor(
    private val id: Long,
    private val backend: SpeechBackend,
) : AutoCloseable {
    /** Whether the session has not ended yet: no terminal event, no cancellation. */
    public val isActive: Boolean
        get() = SpeechSessions.isActive(id)

    /** Closes the microphone but lets the last result arrive, then [SpeechEvent.Stopped]. */
    public fun stop() {
        if (isActive) backend.stop(id, cancel = false)
    }

    /** Stops at once and discards whatever is pending. No event follows. */
    public fun cancel() {
        if (SpeechSessions.unregister(id) != null) backend.stop(id, cancel = true)
    }

    /** Same as [cancel]. */
    override fun close(): Unit = cancel()
}
