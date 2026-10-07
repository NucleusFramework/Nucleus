package dev.nucleusframework.lab.probes.input.scroll

import androidx.compose.runtime.Immutable
import dev.nucleusframework.lab.core.format.fmt
import dev.nucleusframework.lab.core.mvi.Reducer
import dev.nucleusframework.lab.core.mvi.pushFront
import dev.nucleusframework.lab.probes.input.common.fmt

/** What reached Compose at the root, counted per pointer event type. */
@Immutable
data class ScrollCounters(
    val scroll: Int = 0,
    val panStart: Int = 0,
    val panMove: Int = 0,
    val panEnd: Int = 0,
    val scaleStart: Int = 0,
    val scaleChange: Int = 0,
    val scaleEnd: Int = 0,
)

@Immutable
data class ScrollState(
    val counters: ScrollCounters = ScrollCounters(),
    val liveRawY: Float = 0f,
    val offsetPx: Int = 0,
    val maxOffsetPx: Int = 0,
    val fps: Int = 0,
    val gestures: List<GestureStat> = emptyList(),
    /** Newest first: `+gap ms  type  deltas`. */
    val lines: List<String> = emptyList(),
    /** `-Dnucleus.tao.trackpadPanEvents`; `false` turns every trackpad step into Scroll. */
    val panEventsEnabled: Boolean = true,
)

sealed interface ScrollIntent {
    data object Reset : ScrollIntent

    data object CopyTsv : ScrollIntent
}

sealed interface ScrollEvent {
    /** High-rate: one pointer event observed (not logged to the timeline). */
    data class Observed(
        val counters: ScrollCounters,
        val liveRawY: Float,
        val line: String,
    ) : ScrollEvent

    data class Position(
        val offsetPx: Int,
        val maxOffsetPx: Int,
    ) : ScrollEvent

    data class Fps(
        val fps: Int,
    ) : ScrollEvent

    data class GestureFinished(
        val stat: GestureStat,
    ) : ScrollEvent {
        override fun toString(): String =
            "Gesture #${stat.index}: ${stat.events} events, Σraw=${stat.rawSum.fmt()}, " +
                "${stat.pxScrolled} px in ${stat.durationMs} ms, ${stat.fps} fps"
    }

    data object Cleared : ScrollEvent
}

sealed interface ScrollEffect {
    data class Copy(
        val text: String,
    ) : ScrollEffect {
        override fun toString(): String = "Copy(TSV, ${text.lines().size} lines)"
    }
}

object ScrollReducer : Reducer<ScrollState, ScrollEvent> {
    private const val GESTURES = 14
    private const val LINES = 40

    override fun reduce(
        state: ScrollState,
        event: ScrollEvent,
    ): ScrollState =
        when (event) {
            is ScrollEvent.Observed ->
                state.copy(
                    counters = event.counters,
                    liveRawY = event.liveRawY,
                    lines = state.lines.pushFront(event.line, LINES),
                )
            is ScrollEvent.Position -> state.copy(offsetPx = event.offsetPx, maxOffsetPx = event.maxOffsetPx)
            is ScrollEvent.Fps -> state.copy(fps = event.fps)
            is ScrollEvent.GestureFinished -> state.copy(gestures = state.gestures.pushFront(event.stat, GESTURES))
            ScrollEvent.Cleared ->
                state.copy(
                    counters = ScrollCounters(),
                    gestures = emptyList(),
                    lines = emptyList(),
                    liveRawY = 0f,
                )
        }
}

/** Tab-separated dump of [gestures], oldest first, ready to paste into a sheet. */
fun gesturesTsv(
    gestures: List<GestureStat>,
    platform: String,
): String =
    buildString {
        val avgPx = if (gestures.isEmpty()) 0.0 else gestures.map { it.pxScrolled }.average()
        append("# Scroll impact — Nucleus Lab (Tao backend) — $platform\n")
        append("# gestures=${gestures.size}\tavgPxPerGesture=${avgPx.fmt(0)}\n")
        append("idx\tevents\trawSumY\tpxScrolled\tms\tmaxRawAbsY\tpxPerEvent\tfps\n")
        gestures.asReversed().forEach { g ->
            val pxPerEvent = if (g.events == 0) 0f else g.pxScrolled.toFloat() / g.events
            append(
                "${g.index}\t${g.events}\t${g.rawSum.y.fmt()}\t${g.pxScrolled}\t${g.durationMs}\t" +
                    "${g.maxRawAbsY.fmt()}\t${pxPerEvent.fmt()}\t${g.fps}\n",
            )
        }
    }
