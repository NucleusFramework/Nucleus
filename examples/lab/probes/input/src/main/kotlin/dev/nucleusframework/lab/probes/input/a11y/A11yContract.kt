package dev.nucleusframework.lab.probes.input.a11y

import androidx.compose.runtime.Immutable
import dev.nucleusframework.lab.core.mvi.Delivery
import dev.nucleusframework.lab.core.mvi.Reducer
import dev.nucleusframework.lab.core.mvi.pushFront
import dev.nucleusframework.lab.probes.input.a11y.surface.SurfaceTab

@Immutable
data class A11yState(
    val tab: SurfaceTab = SurfaceTab.A11y,
    /** State changes of the surface, newest first, with the thread each action reached Compose on. */
    val actions: List<Delivery> = emptyList(),
    val total: Int = 0,
    val offUiThread: Int = 0,
)

sealed interface A11yIntent {
    data class SelectTab(
        val tab: SurfaceTab,
    ) : A11yIntent

    /** Opens the surface alone in a child process (the CI fixture). */
    data class LaunchIsolated(
        val tab: SurfaceTab,
    ) : A11yIntent

    data object ClearActions : A11yIntent
}

sealed interface A11yEvent {
    data class TabSelected(
        val tab: SurfaceTab,
    ) : A11yEvent

    data class ActionObserved(
        val action: Delivery,
    ) : A11yEvent {
        override fun toString(): String = "ActionObserved(${action.what})"
    }

    data object ActionsCleared : A11yEvent
}

object A11yReducer : Reducer<A11yState, A11yEvent> {
    private const val LOG = 100

    override fun reduce(
        state: A11yState,
        event: A11yEvent,
    ): A11yState =
        when (event) {
            is A11yEvent.TabSelected -> state.copy(tab = event.tab)
            is A11yEvent.ActionObserved ->
                state.copy(
                    actions = state.actions.pushFront(event.action, LOG),
                    total = state.total + 1,
                    offUiThread = state.offUiThread + if (event.action.onUiThread == false) 1 else 0,
                )
            A11yEvent.ActionsCleared -> state.copy(actions = emptyList(), total = 0, offUiThread = 0)
        }
}
