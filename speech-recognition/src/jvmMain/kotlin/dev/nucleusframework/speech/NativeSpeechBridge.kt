package dev.nucleusframework.speech

import dev.nucleusframework.core.runtime.NativeLibraryLoader
import java.util.logging.Level
import java.util.logging.Logger

private const val LIBRARY_NAME = "nucleus_speech"

// Event codes, mirrored by src/main/native/src/lib.rs.
private const val EVENT_STARTED = 0
private const val EVENT_PARTIAL = 1
private const val EVENT_FINAL = 2
private const val EVENT_LEVEL = 3
private const val EVENT_STOPPED = 4
private const val ERROR_PERMISSION = 6
private const val ERROR_UNAVAILABLE = 7
private const val ERROR_LANGUAGE = 8
private const val ERROR_AUDIO = 9
private const val ERROR_BUSY = 10

private val logger = Logger.getLogger("dev.nucleusframework.speech.SpeechRecognition")

internal actual fun platformSpeechBackend(): SpeechBackend = DesktopSpeechBackend

internal actual fun reportListenerFailure(failure: Throwable) {
    logger.log(Level.WARNING, "Speech recognition listener threw", failure)
}

/** Windows and macOS through the Rust bridge; Linux has no native speech service. */
internal object DesktopSpeechBackend : SpeechBackend {
    override val isSupported: Boolean by lazy {
        NativeSpeechBridge.isLoaded && NativeSpeechBridge.nativeIsSupported()
    }

    override val engineName: String
        get() = if (NativeSpeechBridge.isLoaded) NativeSpeechBridge.nativeEngineName() else "none"

    override fun start(
        id: Long,
        options: SpeechRecognitionOptions,
    ) {
        val failure = NativeSpeechBridge.nativeStart(id, options.locale, options.preferOnDevice) ?: return
        val code = failure.substringBefore('\n').toIntOrNull() ?: 0
        throw SpeechException(errorKind(code), failure.substringAfter('\n'))
    }

    override fun stop(
        id: Long,
        cancel: Boolean,
    ) {
        NativeSpeechBridge.nativeStop(id, cancel)
    }
}

internal object NativeSpeechBridge {
    val isLoaded: Boolean by lazy {
        val os = System.getProperty("os.name").orEmpty().lowercase()
        (os.startsWith("windows") || os.startsWith("mac")) &&
            NativeLibraryLoader.load(LIBRARY_NAME, NativeSpeechBridge::class.java)
    }

    @JvmStatic
    external fun nativeIsSupported(): Boolean

    @JvmStatic
    external fun nativeEngineName(): String

    /** Returns null once started, else `"<error code>\n<message>"`. */
    @JvmStatic
    external fun nativeStart(
        id: Long,
        locale: String?,
        preferOnDevice: Boolean,
    ): String?

    @JvmStatic
    external fun nativeStop(
        id: Long,
        cancel: Boolean,
    )

    /** Called from native code, on the backend's thread. */
    @JvmStatic
    fun onNativeEvent(
        id: Long,
        kind: Int,
        text: String?,
        level: Float,
    ) {
        val event =
            when (kind) {
                EVENT_STARTED -> SpeechEvent.Started
                EVENT_PARTIAL -> SpeechEvent.Transcript(text.orEmpty(), isFinal = false)
                EVENT_FINAL -> SpeechEvent.Transcript(text.orEmpty(), isFinal = true)
                EVENT_LEVEL -> SpeechEvent.AudioLevel(if (level.isFinite()) level.coerceIn(0f, 1f) else 0f)
                EVENT_STOPPED -> SpeechEvent.Stopped
                else -> SpeechEvent.Error(errorKind(kind), text.orEmpty())
            }
        SpeechSessions.emit(id, event)
    }
}

/** Unknown codes fall back to [SpeechErrorKind.Other], so a newer bridge still reports its message. */
private fun errorKind(code: Int): SpeechErrorKind =
    when (code) {
        ERROR_PERMISSION -> SpeechErrorKind.PermissionDenied
        ERROR_UNAVAILABLE -> SpeechErrorKind.Unavailable
        ERROR_LANGUAGE -> SpeechErrorKind.Language
        ERROR_AUDIO -> SpeechErrorKind.Audio
        ERROR_BUSY -> SpeechErrorKind.Busy
        else -> SpeechErrorKind.Other
    }
