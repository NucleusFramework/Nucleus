package dev.nucleusframework.speech

// Word comparisons shared by Dictation and the recognizer-side segment tracking. Recognizers
// routinely re-case and re-punctuate what they already sent, so words are compared loosely.

/** The non-empty runs of [text] between whitespace. */
internal fun words(text: String): List<String> {
    val words = mutableListOf<String>()
    var start = -1
    for (index in text.indices) {
        if (text[index].isWhitespace()) {
            if (start >= 0) words += text.substring(start, index)
            start = -1
        } else if (start < 0) {
            start = index
        }
    }
    if (start >= 0) words += text.substring(start)
    return words
}

/** A word ignoring case and surrounding punctuation. */
internal fun loose(word: String): String = word.trim { !it.isLetterOrDigit() }.lowercase()

internal fun looseWords(text: String): List<String> = words(text).map(::loose).filter(String::isNotEmpty)

internal fun looselyEqual(
    a: String,
    b: String,
): Boolean = looseWords(a) == looseWords(b)

/**
 * Whether [new] starts over rather than revising [old], like a recognizer restarting after a
 * pause: it drops most words of a long [old], rather than reformatting a few ("300 and" to "340").
 */
internal fun startsOver(
    old: String,
    new: String,
): Boolean {
    val oldWords = looseWords(old)
    val newWords = looseWords(new)
    val kept = oldWords.count { it in newWords }
    // Long enough to tell, less than half as long, and keeping under a quarter of the words.
    return oldWords.size >= RESTART_MIN_WORDS &&
        newWords.isNotEmpty() &&
        newWords.size * 2 < oldWords.size &&
        kept * RESTART_MAX_KEPT_DIVISOR < oldWords.size
}

private const val RESTART_MIN_WORDS = 6
private const val RESTART_MAX_KEPT_DIVISOR = 4

/** Whether [new] is [old] with words cut off the end. */
internal fun isShortenedRevision(
    old: String,
    new: String,
): Boolean {
    val oldWords = looseWords(old)
    val newWords = looseWords(new)
    return newWords.isNotEmpty() && newWords.size < oldWords.size && oldWords.subList(0, newWords.size) == newWords
}

/** The rest of [text] after its first words, if they loosely match all of [prefix]'s words. */
internal fun looseRemainder(
    text: String,
    prefix: String,
): String? {
    val expected = looseWords(prefix)
    var matched = 0
    var rest = text
    while (matched < expected.size) {
        rest = rest.trimStart()
        if (rest.isEmpty()) return null
        val tokenEnd = rest.indexOfFirst(Char::isWhitespace).let { if (it < 0) rest.length else it }
        val word = loose(rest.substring(0, tokenEnd))
        rest = rest.substring(tokenEnd)
        if (word.isEmpty()) continue
        if (word != expected[matched]) return null
        matched++
    }
    return rest.trim()
}
