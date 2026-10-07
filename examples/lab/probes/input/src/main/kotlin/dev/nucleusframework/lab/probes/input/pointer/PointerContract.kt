package dev.nucleusframework.lab.probes.input.pointer

import androidx.compose.runtime.Immutable
import androidx.compose.ui.geometry.Offset
import dev.nucleusframework.lab.core.mvi.Reducer
import dev.nucleusframework.lab.core.mvi.pushFront
import dev.nucleusframework.lab.probes.input.common.fmt

enum class PointerKind { Enter, Exit, Press, Release, Move }

/** One pointer event over the hover target, reduced to plain data in the composable. */
data class PointerSample(
    val kind: PointerKind,
    val nowMs: Long,
    val position: Offset,
    /** Whether [position] lies inside the target's bounds. */
    val inside: Boolean,
    val buttons: String,
    /** The button that changed on a press/release (`primary`, `secondary`, …), else null. */
    val button: String? = null,
    val modifiers: String,
    val pointerType: String,
)

@Immutable
data class PointerState(
    val hovered: Boolean = false,
    val position: Offset? = null,
    val pointerType: String = "—",
    val buttons: String = "none",
    val modifiers: String = "—",
    val enters: Int = 0,
    val exits: Int = 0,
    /** Exits reported while the last known position was still inside the target. */
    val phantomExits: Int = 0,
    val moves: Int = 0,
    val presses: Map<String, Int> = emptyMap(),
    val releases: Int = 0,
    val lastClickCount: Int = 0,
    val maxClickCount: Int = 0,
    val lastPressMs: Long = 0,
    val lastPressAt: Offset = Offset.Zero,
    val lines: List<String> = emptyList(),
)

sealed interface PointerIntent {
    data object Reset : PointerIntent
}

sealed interface PointerEvent {
    data class Sampled(
        val sample: PointerSample,
    ) : PointerEvent

    data object Cleared : PointerEvent
}

object PointerReducer : Reducer<PointerState, PointerEvent> {
    const val MULTI_CLICK_MS = 500L
    const val MULTI_CLICK_SLOP_PX = 8f
    private const val LINES = 40

    override fun reduce(
        state: PointerState,
        event: PointerEvent,
    ): PointerState =
        when (event) {
            PointerEvent.Cleared -> PointerState(hovered = state.hovered, position = state.position)
            is PointerEvent.Sampled -> sampled(state, event.sample)
        }

    private fun sampled(
        state: PointerState,
        s: PointerSample,
    ): PointerState {
        val base = state.copy(pointerType = s.pointerType, buttons = s.buttons, modifiers = s.modifiers)
        return when (s.kind) {
            PointerKind.Move -> base.copy(moves = state.moves + 1, position = s.position)
            PointerKind.Enter ->
                base
                    .copy(
                        hovered = true,
                        enters = state.enters + 1,
                        position = s.position,
                    ).line(s, "Enter")
            PointerKind.Exit -> {
                // A real exit is reported at (or past) the edge; one whose last move was well inside is phantom.
                val phantom = state.position != null && s.inside
                base
                    .copy(
                        hovered = false,
                        exits = state.exits + 1,
                        phantomExits =
                            state.phantomExits + if (phantom) 1 else 0,
                    ).line(s, if (phantom) "Exit  ⚠ phantom (pointer still inside)" else "Exit")
            }
            PointerKind.Press -> {
                val multi =
                    s.nowMs - state.lastPressMs <= MULTI_CLICK_MS &&
                        (s.position - state.lastPressAt).getDistance() <= MULTI_CLICK_SLOP_PX
                val count = if (multi) state.lastClickCount + 1 else 1
                val button = s.button ?: "?"
                base
                    .copy(
                        presses = state.presses + (button to (state.presses[button] ?: 0) + 1),
                        lastClickCount = count,
                        maxClickCount = maxOf(state.maxClickCount, count),
                        lastPressMs = s.nowMs,
                        lastPressAt = s.position,
                    ).line(s, "Press $button" + if (count > 1) "  ×$count" else "")
            }
            PointerKind.Release -> base.copy(releases = state.releases + 1).line(s, "Release ${s.button ?: "?"}")
        }
    }

    private fun PointerState.line(
        s: PointerSample,
        what: String,
    ) = copy(
        lines =
            lines.pushFront(
                "${what.padEnd(28)} at ${s.position.fmt(0)}  ${s.pointerType}  mods=${s.modifiers}",
                LINES,
            ),
    )
}
