package dev.nucleusframework.speech

/**
 * How a [SpeechSession] recognizes speech.
 *
 * @property locale a BCP-47 language tag such as `"en-US"`, or `null` for the system's speech language
 * @property preferOnDevice use on-device recognition when the platform supports it; otherwise the
 *     system service may need a network connection. Windows SAPI is always on-device.
 */
public data class SpeechRecognitionOptions(
    val locale: String? = null,
    val preferOnDevice: Boolean = true,
)
