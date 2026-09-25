package dev.nucleusframework.speech

/**
 * Turns one SFSpeechRecognizer task's results into transcripts that never drop words already sent.
 *
 * After a pause, on-device recognition closes a segment with one result carrying metadata, then
 * restarts `bestTranscription` from the new words only, without ever setting `isFinal`. Its final
 * result after `endAudio` can hold only the last segment, or nothing at all.
 *
 * Pure logic, kept in common code so it is unit-tested off-device.
 */
internal class TranscriptSegments {
    /** Words sent as a partial but not committed yet. */
    var open: String = ""
        private set

    // The last text committed, and the whole transcription at that point.
    private var committed = ""
    private var closedTranscription = ""

    // Whether this task restarts its transcription after each segment, rather than growing it.
    private var restarts = false

    /** The transcripts to send for one recognition result. */
    fun result(
        transcription: String,
        isFinal: Boolean,
        closesSegment: Boolean,
    ): List<SpeechEvent.Transcript> {
        var text = transcription.trim()
        // A transcription that grows instead of restarting still holds what was committed.
        if (closedTranscription.isNotEmpty() && !restarts) {
            val rest = looseRemainder(text, closedTranscription)
            if (rest != null) {
                text = rest
            } else if (text.isNotEmpty()) {
                restarts = true
            }
        }
        val out = mutableListOf<SpeechEvent.Transcript>()
        if (isFinal) {
            // An empty or cut-short final keeps what was shown, and a repeat of the segment just
            // committed is not committed again.
            if (text.isEmpty() ||
                (open.isEmpty() && looselyEqual(text, committed)) ||
                isShortenedRevision(open, text)
            ) {
                return flush()
            }
            if (startsOver(open, text)) out += flush()
            open = ""
            return out + commit(text)
        }
        if (text.isEmpty() || (closesSegment && open.isEmpty() && looselyEqual(text, committed))) return emptyList()
        if (startsOver(open, text)) out += flush()
        if (closesSegment) {
            open = ""
            closedTranscription = transcription
            return out + commit(text)
        }
        open = text
        out += SpeechEvent.Transcript(text, isFinal = false)
        return out
    }

    /** Commits the words shown but not committed yet, e.g. when a task ends without a final. */
    fun flush(): List<SpeechEvent.Transcript> {
        val text = open
        open = ""
        return commit(text)
    }

    private fun commit(text: String): List<SpeechEvent.Transcript> {
        if (text.isEmpty()) return emptyList()
        committed = text
        return listOf(SpeechEvent.Transcript(text, isFinal = true))
    }
}
