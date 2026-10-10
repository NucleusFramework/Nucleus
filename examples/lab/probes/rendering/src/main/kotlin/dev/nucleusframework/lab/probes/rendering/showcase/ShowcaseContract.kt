package dev.nucleusframework.lab.probes.rendering.showcase

import androidx.compose.runtime.Immutable
import dev.nucleusframework.lab.core.mvi.Reducer

@Immutable
data class ShowcaseState(
    val clicks: Int = 0,
    val blobs: List<Boolean> = List(BLOB_COUNT) { true },
    val blur: Boolean = true,
    val animating: Boolean = true,
    val cursorGlow: Boolean = true,
) {
    companion object {
        const val BLOB_COUNT = 4
    }
}

sealed interface ShowcaseIntent {
    data object Click : ShowcaseIntent

    data class ToggleBlob(
        val index: Int,
    ) : ShowcaseIntent

    data object ToggleBlur : ShowcaseIntent

    data object ToggleAnimation : ShowcaseIntent

    data object ToggleCursorGlow : ShowcaseIntent
}

sealed interface ShowcaseEvent {
    data object Clicked : ShowcaseEvent

    data class BlobToggled(
        val index: Int,
    ) : ShowcaseEvent

    data object BlurToggled : ShowcaseEvent

    data object AnimationToggled : ShowcaseEvent

    data object CursorGlowToggled : ShowcaseEvent
}

object ShowcaseReducer : Reducer<ShowcaseState, ShowcaseEvent> {
    override fun reduce(
        state: ShowcaseState,
        event: ShowcaseEvent,
    ): ShowcaseState =
        when (event) {
            ShowcaseEvent.Clicked -> state.copy(clicks = state.clicks + 1)
            is ShowcaseEvent.BlobToggled ->
                if (event.index !in state.blobs.indices) {
                    state
                } else {
                    state.copy(blobs = state.blobs.mapIndexed { i, on -> if (i == event.index) !on else on })
                }
            ShowcaseEvent.BlurToggled -> state.copy(blur = !state.blur)
            ShowcaseEvent.AnimationToggled -> state.copy(animating = !state.animating)
            ShowcaseEvent.CursorGlowToggled -> state.copy(cursorGlow = !state.cursorGlow)
        }
}
