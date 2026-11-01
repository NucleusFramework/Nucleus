package dev.nucleusframework.speech

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A stand-in text field: applies each replacement and reports back. Ported from robius-speech. */
private class Field(
    var text: String,
    var caret: Int,
) {
    fun apply(
        dictation: Dictation,
        replacement: Replacement?,
    ) {
        replacement ?: return
        text = text.substring(0, replacement.start) + replacement.text + text.substring(replacement.end)
        caret = replacement.start + replacement.text.length
        dictation.applied()
    }

    fun say(
        dictation: Dictation,
        spoken: String,
        isFinal: Boolean,
    ) = apply(dictation, dictation.transcript(spoken, isFinal))

    /** The user types at the caret, as a toolkit event would deliver it. */
    fun type(
        dictation: Dictation,
        typed: String,
    ) {
        dictation.interrupt()
        text = text.substring(0, caret) + typed + text.substring(caret)
        caret += typed.length
        settle(dictation)
    }

    fun settle(dictation: Dictation) = apply(dictation, dictation.settle(text, caret))
}

class DictationTest {
    @Test
    fun partialsReplaceTheUtteranceAndFinalsAppend() {
        val dictation = Dictation("", 0)
        val field = Field("", 0)
        field.say(dictation, "I scream", false)
        assertEquals("I scream", field.text)
        field.say(dictation, "Ice cream.", true)
        assertEquals("Ice cream.", field.text)
        field.say(dictation, "Please", false)
        assertEquals("Ice cream. Please", field.text)
        field.say(dictation, "Please!", true)
        assertEquals("Ice cream. Please!", field.text)
        assertEquals(field.text.length, field.caret)
    }

    @Test
    fun theFirstReplacementStartsARunAndLaterOnesContinueIt() {
        val dictation = Dictation("Hi old friend", 3, 6)
        assertEquals(Replacement(3, 6, "new", continues = false), dictation.transcript("new", false))
        dictation.applied()
        assertEquals(Replacement(3, 6, "dear", continues = true), dictation.transcript("dear", false))
        // A revision that changes nothing is not an edit, nor is the final that confirms it.
        dictation.applied()
        assertNull(dictation.transcript("dear", false))
        assertNull(dictation.transcript("dear", true))
    }

    @Test
    fun aReplacementThatNeverLandedIsSimplyOfferedAgain() {
        val dictation = Dictation("", 0)
        assertEquals(0, dictation.transcript("one", false)?.end)
        assertEquals(Replacement(0, 0, "one two", continues = false), dictation.transcript("one two", false))
        dictation.applied()
        assertEquals(7, dictation.transcript("one two three", true)?.end)
    }

    @Test
    fun speechIsSpacedOffTheDraftButNotOffPunctuationOrBrackets() {
        fun after(
            draft: String,
            spoken: String,
        ): String {
            val dictation = Dictation(draft, draft.length)
            val field = Field(draft, draft.length)
            field.say(dictation, spoken, true)
            return field.text
        }
        assertEquals("hello world", after("hello", "world"))
        assertEquals("hello, world", after("hello,", "world"))
        assertEquals("hello' world", after("hello'", "world"))
        assertEquals("hello world", after("hello ", "world"))
        assertEquals("hello\nworld", after("hello\n", "world"))
        assertEquals("hello(world", after("hello(", "world"))
        assertEquals("你好世界", after("你好", "世界"))
        assertEquals("say enter", after("", "say enter"))

        // Recognizer punctuation joins onto the words before it.
        var dictation = Dictation("(", 1)
        var field = Field("(", 1)
        field.say(dictation, "say enter", true)
        field.say(dictation, ", then stop)", true)
        assertEquals("(say enter, then stop)", field.text)

        // A caret parked mid-draft gets a space on both sides.
        dictation = Dictation("abcdef", 3)
        field = Field("abcdef", 3)
        field.say(dictation, "MID", true)
        assertEquals("abc MID def", field.text)

        // Silence replaces nothing and adds no whitespace.
        dictation = Dictation("keep this", 0, 9)
        assertNull(dictation.transcript(" ", false))
        assertNull(dictation.transcript("", true))
    }

    @Test
    fun selectionOffsetsAreOrderedAndKeptOffSurrogatePairs() {
        val draft = "Hi 🦀, old text today"
        val start = draft.indexOf("old")
        val end = draft.indexOf(" today")
        assertEquals(start, Dictation(draft, end, start).transcript("new text", false)?.start)
        // Inside the crab: floored to its start. Past the end: clamped.
        val inside = draft.indexOf("🦀") + 1
        assertEquals(inside - 1, Dictation(draft, inside).transcript("x", true)?.start)
        assertEquals(draft.length, Dictation(draft, 999).transcript("x", true)?.start)
    }

    @Test
    fun typingMidUtteranceKeepsTheWordsSpokenAfterwards() {
        val dictation = Dictation("", 0)
        val field = Field("", 0)
        field.say(dictation, "hello world", false)
        field.type(dictation, "X")
        assertTrue(dictation.isInterrupted, "still waiting for the utterance to finish")
        // Revisions in the meantime are not written under the user's edit.
        field.say(dictation, "hello world and", false)
        assertEquals("hello worldX", field.text)
        field.say(dictation, "hello world and more", true)
        field.settle(dictation)
        assertEquals("hello worldX and more", field.text)
        assertFalse(dictation.isInterrupted)
    }

    @Test
    fun typingBetweenUtterancesReAnchorsAtOnce() {
        var dictation = Dictation("", 0)
        var field = Field("", 0)
        field.say(dictation, "first", true)
        field.type(dictation, "X")
        assertFalse(dictation.isInterrupted)
        field.say(dictation, "second", true)
        assertEquals("firstX second", field.text)

        // Typing before anything was recognized at all.
        dictation = Dictation("", 0)
        field = Field("", 0)
        field.type(dictation, "typed first")
        field.say(dictation, "spoken after", true)
        assertEquals("typed first spoken after", field.text)
    }

    @Test
    fun aRevisionAfterAnEditNeverUndoesItOrRepeatsItself() {
        // Backspace, then the same utterance again: nothing to add.
        var dictation = Dictation("", 0)
        var field = Field("", 0)
        field.say(dictation, "typo here", false)
        dictation.interrupt()
        field.text = field.text.dropLast(1)
        field.caret -= 1
        field.settle(dictation)
        field.say(dictation, "typo here", false)
        field.say(dictation, "typo here.", true)
        field.settle(dictation)
        assertEquals("typo her", field.text)

        // Wiping the dictated text and typing over it.
        dictation = Dictation("", 0)
        field = Field("", 0)
        field.say(dictation, "wipe me", false)
        dictation.interrupt()
        field.text = "fresh"
        field.caret = 5
        field.settle(dictation)
        field.say(dictation, "wipe me", true)
        field.settle(dictation)
        field.say(dictation, "then more", true)
        assertEquals("fresh then more", field.text)

        // A wholesale revision sharing no words with what was shown is dropped rather than
        // repeated; speech resumes with the next utterance.
        dictation = Dictation("", 0)
        field = Field("", 0)
        field.say(dictation, "keep these words", false)
        dictation.interrupt()
        field.caret = 4
        field.settle(dictation)
        field.say(dictation, "stale revision", true)
        field.settle(dictation)
        assertEquals("keep these words", field.text)
        field.say(dictation, "and more", true)
        assertEquals("keep and more these words", field.text)
    }

    @Test
    fun aFinalThatArrivesDuringTheEditWaitsForIt() {
        var dictation = Dictation("", 0)
        var field = Field("", 0)
        field.say(dictation, "keep these words", false)
        dictation.interrupt()
        assertNull(dictation.transcript("keep these words and more", true))
        field.text = "keep  words"
        field.caret = 5
        field.settle(dictation)
        assertEquals("keep and more words", field.text)

        // Two finals in the same gap both make it in.
        dictation = Dictation("", 0)
        field = Field("", 0)
        field.say(dictation, "hello", false)
        dictation.interrupt()
        assertNull(dictation.transcript("hello world", true))
        assertNull(dictation.transcript("again", true))
        field.text = "hello!"
        field.caret = 6
        field.settle(dictation)
        assertEquals("hello! world again", field.text)
    }

    @Test
    fun settleNoticesChangesThatArrivedWithoutWarning() {
        val dictation = Dictation("", 0)
        val field = Field("", 0)
        field.say(dictation, "hello", false)
        // Nothing changed: not an interruption.
        field.settle(dictation)
        assertFalse(dictation.isInterrupted)
        // The caret moved: an interruption, resolved at the utterance boundary.
        field.caret = 0
        field.settle(dictation)
        assertTrue(dictation.isInterrupted)
        field.say(dictation, "hello there", true)
        field.settle(dictation)
        // The caret follows the words, including the space that keeps them apart.
        assertEquals("there hello", field.text)
        assertEquals(6, field.caret)
    }

    @Test
    fun anInterruptionThatChangedNothingIsCalledOff() {
        var dictation = Dictation("", 0)
        var field = Field("", 0)
        field.say(dictation, "I scream", false)
        dictation.interrupt()
        assertNull(dictation.transcript("I scream for", false), "held while the edit is pending")
        field.settle(dictation)
        assertFalse(dictation.isInterrupted)
        assertEquals("I scream for", field.text, "the held revision lands as soon as nothing changed")
        field.say(dictation, "Ice cream please", true)
        assertEquals("Ice cream please", field.text)

        // The same when the final itself arrived while the interruption was pending.
        dictation = Dictation("", 0)
        field = Field("", 0)
        field.say(dictation, "hello world", false)
        dictation.interrupt()
        assertNull(dictation.transcript("Hello, world.", true))
        field.settle(dictation)
        assertEquals("Hello, world.", field.text)
    }

    @Test
    fun aPartialThatArrivesDuringAnEditIsWrittenAfterIt() {
        val dictation = Dictation("", 0)
        val field = Field("", 0)
        field.say(dictation, "first", true)
        dictation.interrupt()
        assertNull(dictation.transcript("second", false))
        field.text += "X"
        field.caret += 1
        field.settle(dictation)
        assertEquals("firstX second", field.text)
        assertFalse(dictation.isInterrupted)
        field.say(dictation, "second one", true)
        assertEquals("firstX second one", field.text)
    }

    @Test
    fun anEditThatKeepsTheLengthButChangesTheAnchorIsStillAnEdit() {
        val dictation = Dictation("abc", 1)
        val field = Field("abc", 1)
        field.say(dictation, "X", false)
        assertEquals("a X bc" to 4, field.text to field.caret)
        dictation.interrupt()
        field.text = "é X bc"
        field.settle(dictation)
        assertTrue(dictation.isInterrupted)
        field.say(dictation, "X", true)
        field.settle(dictation)
        assertEquals("é X bc", field.text)
    }

    @Test
    fun wordsAlreadyShownAreNeverRemoved() {
        // An empty transcript, partial or final, keeps what is shown.
        var dictation = Dictation("", 0)
        var field = Field("", 0)
        field.say(dictation, "keep me", false)
        field.say(dictation, "", false)
        field.say(dictation, "", true)
        assertEquals("keep me", field.text)
        // A recognizer that starts over after a pause keeps the phrase before it.
        field.say(dictation, "a long phrase that took thirty seconds", false)
        field.say(dictation, "Short phrase.", false)
        field.say(dictation, "Short phrase.", true)
        assertEquals("keep me a long phrase that took thirty seconds Short phrase.", field.text)

        // A final that stops short keeps the words it left out.
        dictation = Dictation("", 0)
        field = Field("", 0)
        field.say(dictation, "hello world and more", false)
        field.say(dictation, "Hello world.", true)
        assertEquals("Hello world. and more", field.text)

        // Reformatting a number as it is spoken is a revision, not a restart.
        dictation = Dictation("", 0)
        field = Field("", 0)
        for (partial in listOf("Three", "300", "300 and", "340", "347 apples")) field.say(dictation, partial, false)
        field.say(dictation, "347 apples.", true)
        assertEquals("347 apples.", field.text)
    }

    @Test
    fun wordsBeyondOnlyReturnsWhatWasNotAlreadyShown() {
        assertEquals("and more", wordsBeyond("hello world", "hello world and more"))
        // Re-casing and re-punctuating is not new speech.
        assertEquals("And more", wordsBeyond("hello world", "Hello, world! And more"))
        assertEquals("", wordsBeyond("hello world", "Hello world."))
        assertEquals("all of it", wordsBeyond("", "all of it"))
        assertEquals("all of it", wordsBeyond("   ", "all of it"))
        // No shared leading words: lose the tail rather than repeat the draft.
        assertEquals("", wordsBeyond("hello world", "goodbye everyone"))
        // A word inserted inside what is shown is not separable either.
        assertEquals("", wordsBeyond("hello world", "hello big world and more"))
        assertEquals("", wordsBeyond("the cat sat", "the cat quickly sat down"))
        // A shorter final is a revision, not new speech.
        assertEquals("", wordsBeyond("hello world and more", "hello world"))
    }
}
