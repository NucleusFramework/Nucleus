package dev.nucleusframework.lab.probes.rendering.conformance

import androidx.compose.runtime.Immutable
import dev.nucleusframework.lab.core.mvi.Reducer

enum class SampleCategory(
    val title: String,
) {
    Text("Text"),
    Layout("Layout"),
    Drawing("Drawing"),
    Layers("Layers & shadows"),
    Images("Images"),
    Lists("Lists & pager"),
    Motion("Animation"),
    State("State & recomposition"),
    Material("Material 3"),
    Expressive("M3 Expressive"),
}

/** One conformance sample: what to look at, and what correct looks like. */
@Immutable
data class Sample(
    val id: String,
    val category: SampleCategory,
    val title: String,
    val expect: String,
)

/** Rendering levers every sample must survive; each is a classic source of Tao-only bugs. */
@Immutable
data class Levers(
    val rtl: Boolean = false,
    val fontScale: Float = 1f,
    /** Extra density multiplier on top of the monitor's (fractional scales: 1.25, 1.5…). */
    val densityScale: Float = 1f,
    val outlines: Boolean = false,
)

@Immutable
data class ConformanceState(
    val category: SampleCategory = SampleCategory.Text,
    val levers: Levers = Levers(),
    val query: String = "",
) {
    fun visibleSamples(catalog: List<Sample>): List<Sample> =
        if (query.isBlank()) {
            catalog.filter { it.category == category }
        } else {
            val words = query.lowercase().split(' ').filter(String::isNotBlank)
            catalog.filter { sample ->
                words.all {
                    it in
                        "${sample.title} ${sample.expect} ${sample.category.title}".lowercase()
                }
            }
        }
}

sealed interface ConformanceIntent {
    data class SelectCategory(
        val category: SampleCategory,
    ) : ConformanceIntent

    data class Search(
        val query: String,
    ) : ConformanceIntent

    data object ToggleRtl : ConformanceIntent

    data class SetFontScale(
        val scale: Float,
    ) : ConformanceIntent

    data class SetDensityScale(
        val scale: Float,
    ) : ConformanceIntent

    data object ToggleOutlines : ConformanceIntent

    data object ResetLevers : ConformanceIntent
}

sealed interface ConformanceEvent {
    data class CategorySelected(
        val category: SampleCategory,
    ) : ConformanceEvent

    data class Searched(
        val query: String,
    ) : ConformanceEvent

    data class LeversChanged(
        val levers: Levers,
    ) : ConformanceEvent
}

object ConformanceReducer : Reducer<ConformanceState, ConformanceEvent> {
    val FontScales = listOf(0.85f, 1f, 1.3f, 2f)
    val DensityScales = listOf(1f, 1.25f, 1.5f, 2f)

    override fun reduce(
        state: ConformanceState,
        event: ConformanceEvent,
    ): ConformanceState =
        when (event) {
            is ConformanceEvent.CategorySelected -> state.copy(category = event.category, query = "")
            is ConformanceEvent.Searched -> state.copy(query = event.query)
            is ConformanceEvent.LeversChanged ->
                state.copy(
                    levers =
                        event.levers.copy(
                            fontScale = event.levers.fontScale.coerceIn(FontScales.first(), FontScales.last()),
                            densityScale =
                                event.levers.densityScale.coerceIn(
                                    DensityScales.first(),
                                    DensityScales.last(),
                                ),
                        ),
                )
        }
}
