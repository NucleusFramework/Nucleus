package dev.nucleusframework.speech

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

private fun partial(text: String) = SpeechEvent.Transcript(text, isFinal = false)

private fun final(text: String) = SpeechEvent.Transcript(text, isFinal = true)

class TranscriptSegmentsTest {
    @Test
    fun partialsThenAFinalCommitTheUtterance() {
        val segments = TranscriptSegments()
        assertEquals(listOf(partial("hello")), segments.result("hello", isFinal = false, closesSegment = false))
        assertEquals(
            listOf(partial("hello world")),
            segments.result(" hello world ", isFinal = false, closesSegment = false),
        )
        assertEquals(
            listOf(final("Hello world.")),
            segments.result("Hello world.", isFinal = true, closesSegment = false),
        )
        assertEquals(emptyList(), segments.flush())
    }

    @Test
    fun aRestartingTranscriptionKeepsEachClosedSegment() {
        // On-device recognition after a pause: metadata closes the segment, then the
        // transcription restarts from the new words only.
        val segments = TranscriptSegments()
        segments.result("first part", isFinal = false, closesSegment = false)
        assertEquals(listOf(final("first part")), segments.result("first part", isFinal = false, closesSegment = true))
        assertEquals(listOf(partial("second")), segments.result("second", isFinal = false, closesSegment = false))
        // The final after endAudio only holds the last segment.
        assertEquals(
            listOf(final("second part")),
            segments.result("second part", isFinal = true, closesSegment = false),
        )
    }

    @Test
    fun aGrowingTranscriptionOnlySendsWhatFollowsTheClosedSegment() {
        val segments = TranscriptSegments()
        segments.result("first part", isFinal = false, closesSegment = true)
        assertEquals(
            listOf(partial("and more")),
            segments.result("First part, and more", isFinal = false, closesSegment = false),
        )
    }

    @Test
    fun anEmptyOrCutShortFinalKeepsWhatWasShown() {
        var segments = TranscriptSegments()
        segments.result("keep all of these words", isFinal = false, closesSegment = false)
        assertEquals(
            listOf(final("keep all of these words")),
            segments.result("keep all", isFinal = true, closesSegment = false),
        )

        segments = TranscriptSegments()
        segments.result("keep me", isFinal = false, closesSegment = false)
        assertEquals(listOf(final("keep me")), segments.result("", isFinal = true, closesSegment = false))
    }

    @Test
    fun aRepeatOfTheCommittedSegmentIsNotCommittedAgain() {
        val segments = TranscriptSegments()
        segments.result("done", isFinal = false, closesSegment = true)
        assertEquals(emptyList(), segments.result("Done.", isFinal = true, closesSegment = false))
    }

    @Test
    fun looseRemainderMatchesWordsLoosely() {
        assertEquals("and more", looseRemainder("Hello, world and more", "hello world"))
        assertEquals("", looseRemainder("hello world", "hello world"))
        assertNull(looseRemainder("goodbye world", "hello world"))
        assertNull(looseRemainder("hello", "hello world"))
    }
}
