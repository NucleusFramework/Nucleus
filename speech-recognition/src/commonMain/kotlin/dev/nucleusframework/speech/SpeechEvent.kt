package dev.nucleusframework.speech

/**
 * What a [SpeechSession] reports, in order: [Started] once the microphone records, then any
 * number of [Transcript] and [AudioLevel] events, then exactly one terminal event, [Stopped] or
 * [Error]. A cancelled session reports nothing more, not even a terminal event.
 */
public sealed interface SpeechEvent {
    /** Permissions were granted and the microphone is actually recording. */
    public data object Started : SpeechEvent

    /**
     * Recognized text. A partial transcript ([isFinal] `false`) replaces the current utterance
     * and may still change; a final one commits it. Recognition then carries on with the next
     * utterance. [Dictation] turns these into text field edits.
     */
    public data class Transcript(
        val text: String,
        val isFinal: Boolean,
    ) : SpeechEvent

    /** Microphone amplitude between 0 and 1, for a level meter or a wave animation. */
    public data class AudioLevel(
        val level: Float,
    ) : SpeechEvent

    /** Capture and the last recognition are done. Terminal. */
    public data object Stopped : SpeechEvent

    /**
     * The session failed, e.g. a denied permission or a missing microphone. Terminal.
     * [message] comes from the platform and is already worded for end users.
     */
    public data class Error(
        val kind: SpeechErrorKind,
        val message: String,
    ) : SpeechEvent
}

internal val SpeechEvent.isTerminal: Boolean
    get() = this is SpeechEvent.Stopped || this is SpeechEvent.Error
