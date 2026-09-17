package dev.nucleusframework.spellcheck

/**
 * A word span inside a source string.
 *
 * @property start inclusive UTF-16 offset
 * @property end exclusive UTF-16 offset
 * @property word the slice `text.substring(start, end)`
 */
public data class SpellcheckWord(
    public val start: Int,
    public val end: Int,
    public val word: String,
)

private const val MAX_CHECKED_LENGTH = 64

/**
 * Walks [text] and yields spellcheck tokens: Unicode letters with optional
 * internal apostrophes (`don't`, `l'eau`). Hyphens split. All-digit tokens
 * and tokens longer than 64 characters are skipped.
 */
public fun iterateWords(text: String): List<SpellcheckWord> {
    if (text.isEmpty()) return emptyList()
    val words = ArrayList<SpellcheckWord>()
    val length = text.length
    var i = 0
    while (i < length) {
        val cp = text.codePointAt(i)
        if (!Character.isLetter(cp)) {
            i += Character.charCount(cp)
            continue
        }
        val start = i
        i = scanWordEnd(text, i + Character.charCount(cp), length)
        val word = text.substring(start, i)
        if (word.length <= MAX_CHECKED_LENGTH && !isAllDigits(word)) {
            words.add(SpellcheckWord(start, i, word))
        }
    }
    return words
}

/**
 * Returns the spans in [text] that [isCorrect] rejects.
 */
public fun misspellingRanges(
    text: String,
    isCorrect: (String) -> Boolean,
): List<SpellcheckWord> = iterateWords(text).filter { word -> !isCorrect(word.word) }

/**
 * Replaces `text[start, end)` with [replacement].
 */
public fun replaceWord(
    text: String,
    start: Int,
    end: Int,
    replacement: String,
): String {
    require(start in 0..text.length) { "start out of range: $start" }
    require(end in start..text.length) { "end out of range: $end" }
    return text.replaceRange(start, end, replacement)
}

/**
 * Replaces the span described by [word] with [replacement].
 */
public fun replaceWord(
    text: String,
    word: SpellcheckWord,
    replacement: String,
): String = replaceWord(text, word.start, word.end, replacement)

/**
 * The word that contains [offset], or `null` if [offset] is not inside a token.
 */
public fun wordAt(
    text: String,
    offset: Int,
): SpellcheckWord? {
    if (offset < 0 || offset > text.length) return null
    val length = text.length
    var i = 0
    while (i < length) {
        val cp = text.codePointAt(i)
        if (!Character.isLetter(cp)) {
            i += Character.charCount(cp)
            continue
        }
        val start = i
        i = scanWordEnd(text, i + Character.charCount(cp), length)
        if (offset in start until i || (offset == i && i == length)) {
            val word = text.substring(start, i)
            if (word.length <= MAX_CHECKED_LENGTH && !isAllDigits(word)) {
                return SpellcheckWord(start, i, word)
            }
            return null
        }
        if (i > offset) return null
    }
    return null
}

private fun scanWordEnd(
    text: String,
    from: Int,
    length: Int,
): Int {
    var i = from
    while (i < length) {
        val next = text.codePointAt(i)
        val nextWidth = advanceOverWordChar(text, i, next, length) ?: return i
        i += nextWidth
    }
    return i
}

/** Returns how many UTF-16 units to consume, or null when the token ends. */
private fun advanceOverWordChar(
    text: String,
    index: Int,
    codePoint: Int,
    length: Int,
): Int? {
    if (isWordContinue(codePoint)) return Character.charCount(codePoint)
    if (codePoint != '\''.code && codePoint != '\u2019'.code) return null
    val after = index + Character.charCount(codePoint)
    return if (after < length && isWordContinue(text.codePointAt(after))) {
        Character.charCount(codePoint)
    } else {
        null
    }
}

private fun isWordContinue(codePoint: Int): Boolean = Character.isLetter(codePoint) || Character.isDigit(codePoint)

private fun isAllDigits(word: String): Boolean {
    var i = 0
    while (i < word.length) {
        val cp = word.codePointAt(i)
        if (!Character.isDigit(cp)) return false
        i += Character.charCount(cp)
    }
    return word.isNotEmpty()
}
