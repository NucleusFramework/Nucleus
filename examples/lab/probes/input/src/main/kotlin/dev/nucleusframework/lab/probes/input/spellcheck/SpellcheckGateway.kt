package dev.nucleusframework.lab.probes.input.spellcheck

import androidx.compose.runtime.Immutable
import dev.nucleusframework.core.runtime.Platform
import dev.nucleusframework.spellcheck.DictionaryLocator
import dev.nucleusframework.spellcheck.SpellChecker
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import java.util.Locale

/** One misspelled word and what the engine proposes for it. */
@Immutable
data class Misspelling(
    val word: String,
    val start: Int,
    val suggestions: List<String>,
)

@Immutable
data class SpellcheckReport(
    val locale: Locale,
    val available: Boolean,
    val dictionaryTag: String?,
    /** Linux: where hunspell dictionaries are looked up, and the pair found for [locale]. */
    val searchDirectories: List<String>,
    val dictionaryFiles: String?,
)

/** Port over the process-wide [SpellChecker] (`spellcheck`): the engine `nucleusApplication` text fields use. */
interface SpellcheckGateway {
    /** Blocking (loads the native engine and dictionary): call off the UI thread. */
    fun load(locale: Locale): SpellcheckReport

    fun misspellings(text: String): List<Misspelling>

    fun check(word: String): Boolean
}

@ContributesBinding(AppScope::class)
@Inject
class NucleusSpellcheckGateway : SpellcheckGateway {
    override fun load(locale: Locale): SpellcheckReport {
        SpellChecker.locale = locale
        val session = SpellChecker.ensureSession(locale)
        val linux = Platform.Current == Platform.Linux
        return SpellcheckReport(
            locale = locale,
            available = session.isAvailable,
            dictionaryTag = session.dictionaryTag,
            searchDirectories =
                if (linux) {
                    DictionaryLocator.defaultDirectories().map {
                        it.toString()
                    }
                } else {
                    emptyList()
                },
            dictionaryFiles =
                if (linux) {
                    DictionaryLocator.find(locale)?.let { "${it.tag}: ${it.aff} + ${it.dic}" }
                        ?: "none found"
                } else {
                    null
                },
        )
    }

    override fun misspellings(text: String): List<Misspelling> =
        SpellChecker.misspellings(text).take(MAX_WORDS).map {
            Misspelling(it.word, it.start, SpellChecker.suggest(it.word).take(MAX_SUGGESTIONS))
        }

    override fun check(word: String): Boolean = SpellChecker.check(word)

    private companion object {
        const val MAX_WORDS = 12
        const val MAX_SUGGESTIONS = 5
    }
}
