package dev.nucleusframework.speech

/** The cause of a speech failure, so each one can be handled without matching on message text. */
public enum class SpeechErrorKind {
    /**
     * The user denied permission, or the app lacks the privacy declaration needed to ask
     * (`NSMicrophoneUsageDescription` / `NSSpeechRecognitionUsageDescription` on Apple).
     * Usually worth pointing the user to the system privacy settings.
     */
    PermissionDenied,

    /** No audio input or speech recognition service exists here; retrying will never work. */
    Unavailable,

    /** The recognizer does not support the requested language. */
    Language,

    /** The microphone is missing, busy, or changed mid-session. Starting again likely works. */
    Audio,

    /** Another session is already active; only one can run at a time. */
    Busy,

    /** Anything else the platform reported. */
    Other,
}

/** Thrown by [SpeechRecognition.start] when a session cannot even begin. */
public class SpeechException(
    public val kind: SpeechErrorKind,
    message: String,
) : Exception(message)
