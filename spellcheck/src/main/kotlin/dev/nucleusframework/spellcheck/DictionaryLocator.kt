package dev.nucleusframework.spellcheck

import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale

/**
 * Resolves a locale to a matching Hunspell/Myspell `.aff`/`.dic` pair on disk.
 */
public object DictionaryLocator {
    /**
     * Default Linux directories that ship Hunspell/Myspell dictionaries.
     */
    public fun defaultDirectories(): List<Path> {
        val fromEnv =
            System
                .getenv("DICPATH")
                ?.split(':')
                ?.filter { it.isNotBlank() }
                ?.map { Path.of(it) }
                .orEmpty()
        val xdgData =
            System
                .getenv("XDG_DATA_HOME")
                ?.takeIf { it.isNotBlank() }
                ?.let { Path.of(it, "hunspell") }
                ?: Path.of(System.getProperty("user.home"), ".local", "share", "hunspell")
        return fromEnv +
            listOf(
                Path.of("/usr/share/hunspell"),
                Path.of("/usr/share/myspell/dicts"),
                Path.of("/usr/share/myspell"),
                Path.of("/usr/local/share/hunspell"),
                xdgData,
            )
    }

    /**
     * Finds `.aff`/`.dic` for [locale] under [directories].
     *
     * Tries the exact `language_COUNTRY` tag, then the language-only file,
     * then a short alias list (`en` → `en_US`/`en_GB`, `fr` → `fr_FR`, …).
     *
     * @return the pair, or `null` when no matching dictionary exists
     */
    public fun find(
        locale: Locale,
        directories: List<Path> = defaultDirectories(),
    ): DictionaryFiles? {
        val candidates = localeCandidates(locale)
        for (tag in candidates) {
            for (dir in directories) {
                val aff = dir.resolve("$tag.aff")
                val dic = dir.resolve("$tag.dic")
                if (Files.isRegularFile(aff) && Files.isRegularFile(dic)) {
                    return DictionaryFiles(aff, dic, tag)
                }
            }
        }
        return null
    }

    internal fun localeCandidates(locale: Locale): List<String> {
        val language = locale.language.orEmpty().lowercase(Locale.ENGLISH)
        val country = locale.country.orEmpty().uppercase(Locale.ENGLISH)
        if (language.isEmpty() || language == "c" || language == "posix") {
            return listOf("en_US", "en_GB", "en")
        }
        val exact = if (country.isNotEmpty()) "${language}_$country" else language
        val aliases =
            when (language) {
                "en" -> listOf("en_US", "en_GB", "en")
                "fr" -> listOf("fr_FR", "fr")
                "de" -> listOf("de_DE", "de")
                "es" -> listOf("es_ES", "es")
                "pt" -> listOf("pt_BR", "pt_PT", "pt")
                "it" -> listOf("it_IT", "it")
                "nl" -> listOf("nl_NL", "nl")
                "ru" -> listOf("ru_RU", "ru")
                else -> listOf(language)
            }
        return (listOf(exact, language) + aliases).distinct()
    }
}

/**
 * Paths of a matching Hunspell affix + dictionary pair.
 *
 * @property aff path to the `.aff` file
 * @property dic path to the `.dic` file
 * @property tag locale tag that matched (e.g. `en_US`)
 */
public data class DictionaryFiles(
    public val aff: Path,
    public val dic: Path,
    public val tag: String,
)
