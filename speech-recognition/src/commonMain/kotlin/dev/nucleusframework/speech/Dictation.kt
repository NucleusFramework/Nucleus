package dev.nucleusframework.speech

/**
 * One edit to make to a text field: replace `start until end` with [text].
 *
 * Offsets are UTF-16 indices, the way `String` and Compose's `TextRange` count them.
 *
 * @property continues whether this revises what an earlier replacement wrote rather than writing
 *     somewhere new, so a whole run of revisions of one phrase can be grouped into one undo step
 */
public data class Replacement(
    val start: Int,
    val end: Int,
    val text: String,
    val continues: Boolean,
)

/**
 * Puts dictated words into a text field, knowing nothing about any UI toolkit.
 *
 * Speech replaces the selection given at construction and carries on from there. Feed each
 * [SpeechEvent.Transcript] to [transcript], apply the [Replacement] it returns, then call
 * [applied]. Partials revise the current utterance in place and finals commit it, with a space
 * kept between the dictated words and whatever the user typed (but not before punctuation,
 * after an opening bracket, or between CJK characters).
 *
 * When the user is about to change the field (a keystroke, a click that moves the caret), call
 * [interrupt] first, and [settle] with the field's new text and selection once the edit has
 * landed: dictation then picks up again from the caret, adding only the words not on screen
 * yet. Calling [settle] after every event is fine; it also notices unannounced changes. Treat
 * an IME composition as an interruption, and only settle once it has committed.
 *
 * Not thread-safe: use it from the UI thread.
 */
public class Dictation(
    text: String,
    selectionStart: Int,
    selectionEnd: Int = selectionStart,
) {
    // The field's text when we last anchored, and the range of it we write over.
    private var draft = ""
    private var start = 0
    private var end = 0

    // Utterances the recognizer has finished, and the one it is still revising.
    private var committed = ""
    private var pending = ""

    // What has actually been written over start until end; null until the first replacement lands.
    private var shown: String? = null

    // A replacement handed out but not yet confirmed by applied().
    private var offered: String? = null

    // Set while the user is editing: true when an utterance was mid-flight as they started,
    // so re-anchoring waits for it to finish.
    private var interrupted: Boolean? = null

    init {
        anchor(text, selectionStart, selectionEnd)
    }

    /** Whether an [interrupt] is waiting for [settle]. */
    public val isInterrupted: Boolean
        get() = interrupted != null

    /**
     * A transcript from the recognizer: a partial revises the current utterance, a final
     * commits it. Returns the edit that brings the field up to date, if any; apply it and
     * call [applied].
     */
    public fun transcript(
        text: String,
        isFinal: Boolean,
    ): Replacement? {
        // Never lose words already received: an empty transcript changes nothing, one that
        // starts over commits what it replaces, and one that stops short keeps the rest.
        val trimmed = text.trim()
        if (trimmed.isNotEmpty()) {
            val previous = pending
            pending = trimmed
            if (startsOver(previous, trimmed)) {
                committed = joinWords(committed, previous)
            } else {
                pending = joinWords(pending, wordsBeyond(trimmed, previous))
            }
        }
        if (isFinal) {
            committed = joinWords(committed, pending)
            pending = ""
        }
        if (interrupted != null) {
            if (isFinal) interrupted = false
            return null
        }
        return offer()
    }

    /** The replacement handed out last has been applied to the field. */
    public fun applied() {
        offered?.let { shown = it }
        offered = null
    }

    /** The user is changing the field or moving the caret: nothing is written until [settle]. */
    public fun interrupt() {
        if (interrupted == null) {
            interrupted = pending.isNotEmpty()
            offered = null
        }
    }

    /**
     * The field's text and selection now that the current event has been handled. Anything we
     * did not write ourselves counts as an interruption, and one that changed nothing (End with
     * the caret already at the end) is called off. Once an interrupted utterance has finished,
     * dictation re-anchors at the caret and returns whatever was said since that is not on
     * screen yet, if anything.
     */
    public fun settle(
        text: String,
        selectionStart: Int,
        selectionEnd: Int = selectionStart,
    ): Replacement? {
        val from = minOf(selectionStart, selectionEnd)
        val to = maxOf(selectionStart, selectionEnd)
        if (matches(text, from, to)) {
            interrupted = null
            return offer()
        }
        interrupt()
        if (interrupted != false) return null
        val unseen = wordsBeyond(shown.orEmpty(), committed)
        anchor(text, from, to)
        committed = unseen
        shown = null
        offered = null
        interrupted = null
        return offer()
    }

    private fun anchor(
        text: String,
        selectionStart: Int,
        selectionEnd: Int,
    ) {
        draft = text
        start = floorCharBoundary(text, minOf(selectionStart, selectionEnd))
        end = floorCharBoundary(text, maxOf(selectionStart, selectionEnd))
    }

    private fun offer(): Replacement? {
        val insertion = insertion()
        val current = shown
        if (if (current == null) insertion.isEmpty() else current == insertion) return null
        offered = insertion
        val replacedEnd = if (current == null) end else start + current.length
        return Replacement(start, replacedEnd, insertion, continues = current != null)
    }

    /** Whether the field holds exactly what we last left in it. */
    private fun matches(
        text: String,
        from: Int,
        to: Int,
    ): Boolean {
        val current = shown ?: return text == draft && from == start && to == end
        val caret = start + current.length
        return from == caret &&
            to == caret &&
            text.length == draft.length - (end - start) + current.length &&
            text.startsWith(draft.substring(0, start)) &&
            text.startsWith(current, start) &&
            text.startsWith(draft.substring(end), caret)
    }

    /** Everything said since the anchor, spaced off the draft around it. */
    private fun insertion(): String {
        val spoken = joinWords(committed, pending)
        if (spoken.isEmpty()) return spoken
        val before = if (needsSpace(draft.substring(0, start), spoken)) " " else ""
        val after = if (needsSpace(spoken, draft.substring(end))) " " else ""
        return before + spoken + after
    }
}

/** Clamps [index] into [text] and moves it off the middle of a surrogate pair. */
private fun floorCharBoundary(
    text: String,
    index: Int,
): Int {
    val clamped = index.coerceIn(0, text.length)
    return if (clamped in 1 until text.length &&
        text[clamped].isLowSurrogate() &&
        text[clamped - 1].isHighSurrogate()
    ) {
        clamped - 1
    } else {
        clamped
    }
}

/**
 * The part of [spoken] beyond what the user can already see in [shown]. Returns nothing when
 * the two do not share all of [shown]'s words up front, so nothing is ever inserted twice.
 */
internal fun wordsBeyond(
    shown: String,
    spoken: String,
): String {
    if (shown.isBlank()) return spoken.trim()
    val shownWords = words(shown).map(::loose)
    var matched = 0
    var rest = spoken.trim()
    for (word in words(spoken)) {
        if (matched >= shownWords.size || loose(word) != shownWords[matched]) break
        matched++
        rest = rest.substring(word.length).trimStart()
    }
    // Every visible word must be accounted for: a revision that inserted a word inside them
    // has no safely separable tail.
    return if (matched < shownWords.size) "" else rest
}

private fun joinWords(
    left: String,
    right: String,
): String = if (needsSpace(left, right)) "$left $right" else left + right

/**
 * Whether dictated text needs a space to keep it off what precedes it. The exceptions are the
 * cases where joining is what was meant: an opening bracket just typed, punctuation the
 * recognizer supplies, and scripts that do not space their words. An apostrophe only joins on
 * the right, for endings like `'s`.
 */
private fun needsSpace(
    left: String,
    right: String,
): Boolean {
    val last = left.lastOrNull() ?: return false
    val first = right.firstOrNull() ?: return false
    return !last.isWhitespace() &&
        !first.isWhitespace() &&
        last !in "([{" &&
        first !in ".,!?:;)]}'’" &&
        !last.isCjk() &&
        !first.isCjk()
}

// CJK punctuation and kana, unified ideographs (with extension A), Hangul syllables, compatibility ideographs.
private fun Char.isCjk(): Boolean = this in '　'..'ヿ' || this in '㐀'..'鿿' || this in '가'..'힯' || this in '豈'..'﫿'
