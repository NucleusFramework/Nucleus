package dev.nucleusframework.spellcheck

/**
 * Payload used to overload a text-field context menu for one target word.
 *
 * @property word the misspelled token
 * @property range span of [word] in the source string, when known
 * @property suggestions replacement candidates (at most [maxSuggestions])
 * @property addToDictionaryLabel label for the persist-word item
 */
public data class SpellcheckMenuModel(
    public val word: String,
    public val range: SpellcheckWord?,
    public val suggestions: List<String>,
    public val addToDictionaryLabel: String = DEFAULT_ADD_TO_DICTIONARY_LABEL,
) {
    /** Holds the default "Add to dictionary" label. */
    public companion object {
        /**
         * Default English label matching Electron/Chrome's "Add to dictionary".
         */
        public const val DEFAULT_ADD_TO_DICTIONARY_LABEL: String = "Add to dictionary"
    }
}

private const val DEFAULT_MAX_SUGGESTIONS = 5

/**
 * Builds a menu model for [word] using [session].
 *
 * Returns `null` when [word] is empty, the session is a no-op, or the word
 * checks as correct.
 */
public fun buildSpellcheckMenuModel(
    word: String,
    session: SpellcheckSession,
    range: SpellcheckWord? = null,
    maxSuggestions: Int = DEFAULT_MAX_SUGGESTIONS,
): SpellcheckMenuModel? {
    if (word.isEmpty() || !session.isAvailable) return null
    if (session.check(word)) return null
    val suggestions = session.suggest(word).take(maxSuggestions)
    return SpellcheckMenuModel(
        word = word,
        range = range,
        suggestions = suggestions,
    )
}

/**
 * Builds a menu model for the token at [offset] in [text].
 *
 * Returns `null` when there is no misspelled token at that offset.
 */
public fun buildSpellcheckMenuModel(
    text: String,
    offset: Int,
    session: SpellcheckSession,
    maxSuggestions: Int = DEFAULT_MAX_SUGGESTIONS,
): SpellcheckMenuModel? {
    val range = wordAt(text, offset) ?: return null
    return buildSpellcheckMenuModel(
        word = range.word,
        session = session,
        range = range,
        maxSuggestions = maxSuggestions,
    )
}

/**
 * Applies [suggestion] to the span of [model] in [text].
 *
 * When [model] has no range, replaces the first exact occurrence of [model.word].
 */
public fun applySuggestion(
    text: String,
    model: SpellcheckMenuModel,
    suggestion: String,
): String {
    val range = model.range
    if (range != null) {
        return replaceWord(text, range, suggestion)
    }
    val index = text.indexOf(model.word)
    if (index < 0) return text
    return replaceWord(text, index, index + model.word.length, suggestion)
}
