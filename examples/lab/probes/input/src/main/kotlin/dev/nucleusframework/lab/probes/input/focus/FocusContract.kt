package dev.nucleusframework.lab.probes.input.focus

import androidx.compose.runtime.Immutable
import dev.nucleusframework.lab.core.mvi.Reducer
import dev.nucleusframework.lab.core.mvi.pushFront

/** The focus targets, in their expected Tab order. */
val FocusTargets: List<String> = listOf("Name", "Email", "Subscribe", "Notes", "Cancel", "Submit")

@Immutable
data class ChildConfig(
    val enabled: Boolean = true,
    val focusable: Boolean = true,
)

@Immutable
data class FocusState(
    val focused: String? = null,
    /** Targets in the order they gained focus since the last reset. */
    val order: List<String> = emptyList(),
    val windowFocused: Boolean? = null,
    val windowFocusChanges: Int = 0,
    /** What had focus when the window last lost it; must come back on reactivation. */
    val focusedBeforeDeactivation: String? = null,
    val restoreFailures: Int = 0,
    val child: ChildConfig = ChildConfig(),
    val childOpen: Boolean = false,
    val childInputs: List<String> = emptyList(),
    val lines: List<String> = emptyList(),
)

sealed interface FocusIntent {
    data class SetChild(
        val config: ChildConfig,
    ) : FocusIntent

    data object OpenChild : FocusIntent

    data object CloseChild : FocusIntent

    data object Reset : FocusIntent
}

sealed interface FocusEvent {
    data class Gained(
        val target: String,
    ) : FocusEvent

    data class Lost(
        val target: String,
    ) : FocusEvent

    data class WindowFocus(
        val focused: Boolean,
    ) : FocusEvent

    data class ChildConfigured(
        val config: ChildConfig,
    ) : FocusEvent

    data class ChildOpened(
        val open: Boolean,
    ) : FocusEvent

    /** Pointer or key input that reached the child window (it must not when disabled). */
    data class ChildInput(
        val what: String,
    ) : FocusEvent

    data object Cleared : FocusEvent
}

object FocusReducer : Reducer<FocusState, FocusEvent> {
    private const val LINES = 40

    override fun reduce(
        state: FocusState,
        event: FocusEvent,
    ): FocusState =
        when (event) {
            is FocusEvent.Gained -> {
                // Regaining focus right after the window came back: was it the same target?
                val restoring = state.focusedBeforeDeactivation
                val failed =
                    restoring != null &&
                        state.windowFocused == true &&
                        state.focused == null &&
                        restoring != event.target
                state
                    .copy(
                        focused = event.target,
                        order = state.order + event.target,
                        focusedBeforeDeactivation = null,
                        restoreFailures = state.restoreFailures + if (failed) 1 else 0,
                    ).line("focus → ${event.target}" + if (failed) "  ⚠ expected $restoring back" else "")
            }
            is FocusEvent.Lost ->
                state
                    .copy(
                        focused =
                            if (state.focused == event.target) {
                                null
                            } else {
                                state.focused
                            },
                    ).line("focus ✕ ${event.target}")
            is FocusEvent.WindowFocus ->
                if (state.windowFocused == event.focused) {
                    state
                } else {
                    state
                        .copy(
                            windowFocused = event.focused,
                            windowFocusChanges = state.windowFocusChanges + 1,
                            focusedBeforeDeactivation =
                                if (event.focused) state.focusedBeforeDeactivation else state.focused,
                        ).line(if (event.focused) "window focused" else "window lost focus")
                }
            is FocusEvent.ChildConfigured -> state.copy(child = event.config)
            is FocusEvent.ChildOpened ->
                state.copy(
                    childOpen = event.open,
                    childInputs = if (event.open) emptyList() else state.childInputs,
                )
            is FocusEvent.ChildInput -> state.copy(childInputs = state.childInputs.pushFront(event.what, LINES))
            FocusEvent.Cleared ->
                FocusState(
                    focused = state.focused,
                    windowFocused = state.windowFocused,
                    child = state.child,
                    childOpen = state.childOpen,
                )
        }

    private fun FocusState.line(text: String) = copy(lines = lines.pushFront(text, LINES))
}

/** Whether [order] walks [FocusTargets] one neighbour at a time (Tab or Shift+Tab, wrapping), never skipping. */
fun isSequentialOrder(order: List<String>): Boolean {
    if (order.size < 2) return true
    return order.zipWithNext().all { (a, b) ->
        val ia = FocusTargets.indexOf(a)
        val ib = FocusTargets.indexOf(b)
        ia >= 0 &&
            ib >= 0 &&
            (
                ib == ia + 1 ||
                    ib == ia - 1 ||
                    (ia == FocusTargets.lastIndex && ib == 0) ||
                    (ia == 0 && ib == FocusTargets.lastIndex)
            )
    }
}
