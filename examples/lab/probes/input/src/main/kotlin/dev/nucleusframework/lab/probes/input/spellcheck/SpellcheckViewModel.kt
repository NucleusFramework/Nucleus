package dev.nucleusframework.lab.probes.input.spellcheck

import androidx.lifecycle.ViewModel
import dev.nucleusframework.lab.core.mvi.MviViewModel
import dev.nucleusframework.lab.core.timeline.Severity
import dev.nucleusframework.lab.core.timeline.Timeline
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import java.util.Locale

@ViewModelKey
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
@Inject
class SpellcheckViewModel(
    private val gateway: SpellcheckGateway,
    timeline: Timeline,
) : MviViewModel<SpellcheckState, SpellcheckIntent, SpellcheckEvent, Nothing>(
        SpellcheckState(),
        SpellcheckReducer,
        timeline,
        SpellcheckProbe.ID,
    ) {
    init {
        launch { load(state.value.locale) }
    }

    /** Keystroke-rate: edits only touch the state, the resulting analysis reaches the timeline. */
    fun onSampleEdited(text: String) {
        reduceSilently(SpellcheckEvent.SampleChanged(text))
        launch { analyse() }
    }

    override suspend fun handle(intent: SpellcheckIntent) {
        when (intent) {
            is SpellcheckIntent.SelectLocale -> load(intent.locale)
            is SpellcheckIntent.CheckWord -> {
                val word = intent.word.trim()
                if (word.isNotEmpty()) {
                    dispatch(
                        SpellcheckEvent.WordChecked(word, io { gateway.check(word) }),
                    )
                }
            }
        }
    }

    private suspend fun load(locale: Locale) {
        dispatch(SpellcheckEvent.Loading(locale))
        val report = io { gateway.load(locale) }
        // No dictionary is a valid outcome (fields become no-ops), not an error — but worth a flag.
        dispatch(SpellcheckEvent.Loaded(report), if (report.available) Severity.Info else Severity.Warning)
        analyse()
    }

    private suspend fun analyse() {
        val text = state.value.sample
        val words = io { gateway.misspellings(text) }
        // Intents run concurrently: drop an analysis the user has already typed past.
        if (state.value.sample == text && words != state.value.misspellings) dispatch(SpellcheckEvent.Analysed(words))
    }
}
