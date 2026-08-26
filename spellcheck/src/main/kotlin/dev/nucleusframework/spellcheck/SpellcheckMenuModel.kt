package dev.nucleusframework.spellcheck

import java.util.Locale

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
    public val addToDictionaryLabel: String = localizedAddToDictionaryLabel(),
) {
    /** Holds the default "Add to dictionary" label. */
    public companion object {
        /**
         * Default English label matching Electron/Chrome's "Add to dictionary".
         */
        public const val DEFAULT_ADD_TO_DICTIONARY_LABEL: String = "Add to dictionary"

        /**
         * Persist-word label in [locale], matching how Compose localizes
         * Cut / Copy / Paste from the JVM default locale.
         *
         * Unknown locales fall back to [DEFAULT_ADD_TO_DICTIONARY_LABEL].
         */
        public fun localizedAddToDictionaryLabel(locale: Locale = Locale.getDefault()): String {
            val language = locale.language.lowercase(Locale.ROOT)
            val country = locale.country.uppercase(Locale.ROOT)
            val tag = if (country.isEmpty()) language else "${language}_$country"
            return ADD_TO_DICTIONARY_LABELS[tag]
                ?: ADD_TO_DICTIONARY_LABELS[canonicalLanguage(language)]
                ?: DEFAULT_ADD_TO_DICTIONARY_LABEL
        }

        private fun canonicalLanguage(language: String): String =
            when (language) {
                "in" -> "id"
                "iw" -> "he"
                else -> language
            }
    }
}

// Chrome / Electron "Add to dictionary" phrasing, keyed by Java locale
// (`language` or `language_COUNTRY`). Country tags override the language.
private val ADD_TO_DICTIONARY_LABELS: Map<String, String> =
    mapOf(
        "ar" to "إضافة إلى القاموس",
        "bg" to "Добавяне към речника",
        "ca" to "Afegeix al diccionari",
        "cs" to "Přidat do slovníku",
        "da" to "Føj til ordbog",
        "de" to "Zum Wörterbuch hinzufügen",
        "el" to "Προσθήκη στο λεξικό",
        "en" to SpellcheckMenuModel.DEFAULT_ADD_TO_DICTIONARY_LABEL,
        "es" to "Añadir al diccionario",
        "es_MX" to "Agregar al diccionario",
        "es_US" to "Agregar al diccionario",
        "es_419" to "Agregar al diccionario",
        "fi" to "Lisää sanakirjaan",
        "fr" to "Ajouter au dictionnaire",
        "he" to "הוסף למילון",
        "hi" to "शब्दकोश में जोड़ें",
        "hr" to "Dodaj u rječnik",
        "hu" to "Hozzáadás a szótárhoz",
        "id" to "Tambahkan ke kamus",
        "it" to "Aggiungi al dizionario",
        "ja" to "辞書に追加",
        "ko" to "사전에 추가",
        "ms" to "Tambah ke kamus",
        "nb" to "Legg til i ordliste",
        "nl" to "Toevoegen aan woordenboek",
        "nn" to "Legg til i ordliste",
        "no" to "Legg til i ordliste",
        "pl" to "Dodaj do słownika",
        "pt" to "Adicionar ao dicionário",
        "pt_BR" to "Adicionar ao dicionário",
        "pt_PT" to "Adicionar ao dicionário",
        "ro" to "Adaugă în dicționar",
        "ru" to "Добавить в словарь",
        "sk" to "Pridať do slovníka",
        "sl" to "Dodaj v slovar",
        "sv" to "Lägg till i ordlista",
        "th" to "เพิ่มในพจนานุกรม",
        "tr" to "Sözlüğe ekle",
        "uk" to "Додати до словника",
        "vi" to "Thêm vào từ điển",
        "zh" to "添加到字典",
        "zh_CN" to "添加到字典",
        "zh_SG" to "添加到字典",
        "zh_TW" to "加入字典",
        "zh_HK" to "加入字典",
    )

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
        addToDictionaryLabel = SpellcheckMenuModel.localizedAddToDictionaryLabel(),
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
