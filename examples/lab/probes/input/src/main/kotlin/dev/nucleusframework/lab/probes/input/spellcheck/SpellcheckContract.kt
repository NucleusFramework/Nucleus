package dev.nucleusframework.lab.probes.input.spellcheck

import androidx.compose.runtime.Immutable
import dev.nucleusframework.lab.core.mvi.Reducer
import java.util.Locale

/** Languages to switch between; the system one first. Hebrew covers a right-to-left script. */
val SpellcheckLocales: List<Locale> =
    listOf(Locale.getDefault(), Locale.US, Locale.FRANCE, Locale.GERMANY, Locale.forLanguageTag("he-IL")).distinctBy {
        it.toLanguageTag()
    }

@Immutable
data class SpellcheckState(
    val locale: Locale = Locale.getDefault(),
    val loading: Boolean = false,
    val report: SpellcheckReport? = null,
    val sample: String = "I recieve the teh package tommorow. Please chek the adress.",
    val misspellings: List<Misspelling> = emptyList(),
    val probeWord: String = "",
    val probeResult: Boolean? = null,
)

sealed interface SpellcheckIntent {
    data class SelectLocale(
        val locale: Locale,
    ) : SpellcheckIntent

    data class CheckWord(
        val word: String,
    ) : SpellcheckIntent
}

sealed interface SpellcheckEvent {
    data class Loading(
        val locale: Locale,
    ) : SpellcheckEvent

    data class Loaded(
        val report: SpellcheckReport,
    ) : SpellcheckEvent

    data class SampleChanged(
        val text: String,
    ) : SpellcheckEvent

    data class Analysed(
        val misspellings: List<Misspelling>,
    ) : SpellcheckEvent {
        override fun toString(): String =
            "Analysed(${misspellings.size} misspelled: ${misspellings.joinToString { it.word }})"
    }

    data class WordChecked(
        val word: String,
        val correct: Boolean,
    ) : SpellcheckEvent
}

object SpellcheckReducer : Reducer<SpellcheckState, SpellcheckEvent> {
    override fun reduce(
        state: SpellcheckState,
        event: SpellcheckEvent,
    ): SpellcheckState =
        when (event) {
            is SpellcheckEvent.Loading -> state.copy(locale = event.locale, loading = true)
            is SpellcheckEvent.Loaded -> state.copy(report = event.report, loading = false)
            is SpellcheckEvent.SampleChanged -> state.copy(sample = event.text)
            is SpellcheckEvent.Analysed -> state.copy(misspellings = event.misspellings)
            is SpellcheckEvent.WordChecked -> state.copy(probeWord = event.word, probeResult = event.correct)
        }
}
