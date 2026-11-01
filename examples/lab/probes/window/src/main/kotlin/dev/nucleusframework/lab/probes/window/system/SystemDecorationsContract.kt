package dev.nucleusframework.lab.probes.window.system

import androidx.compose.runtime.Immutable
import dev.nucleusframework.lab.core.mvi.Reducer

/** The two system-decorated surfaces the probe opens. */
enum class SystemSurface { Window, Dialog }

@Immutable
data class SystemDecorationsState(
    val open: Set<SystemSurface> = emptySet(),
    /** Puts a Nucleus `TitleBar` in the window, to check it collapses to plain content. */
    val withTitleBar: Boolean = false,
    /** Presses on the window's top-left target; under a header bar they must land at its real position. */
    val targetPresses: Int = 0,
    val lastPress: String? = null,
)

sealed interface SystemDecorationsIntent {
    data class Open(
        val surface: SystemSurface,
    ) : SystemDecorationsIntent

    data class Close(
        val surface: SystemSurface,
    ) : SystemDecorationsIntent

    data class SetWithTitleBar(
        val enabled: Boolean,
    ) : SystemDecorationsIntent

    data class TargetPressed(
        val x: Float,
        val y: Float,
    ) : SystemDecorationsIntent
}

sealed interface SystemDecorationsEvent {
    data class OpenChanged(
        val surface: SystemSurface,
        val open: Boolean,
    ) : SystemDecorationsEvent

    data class WithTitleBarChanged(
        val enabled: Boolean,
    ) : SystemDecorationsEvent

    data class TargetPressed(
        val position: String,
    ) : SystemDecorationsEvent
}

object SystemDecorationsReducer : Reducer<SystemDecorationsState, SystemDecorationsEvent> {
    override fun reduce(
        state: SystemDecorationsState,
        event: SystemDecorationsEvent,
    ): SystemDecorationsState =
        when (event) {
            is SystemDecorationsEvent.OpenChanged ->
                state.copy(open = if (event.open) state.open + event.surface else state.open - event.surface)
            is SystemDecorationsEvent.WithTitleBarChanged -> state.copy(withTitleBar = event.enabled)
            is SystemDecorationsEvent.TargetPressed ->
                state.copy(targetPresses = state.targetPresses + 1, lastPress = event.position)
        }
}
