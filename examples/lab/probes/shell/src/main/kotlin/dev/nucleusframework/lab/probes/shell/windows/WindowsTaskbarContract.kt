package dev.nucleusframework.lab.probes.shell.windows

import androidx.compose.runtime.Immutable
import dev.nucleusframework.lab.core.Availability
import dev.nucleusframework.lab.core.mvi.CallOutcome
import dev.nucleusframework.lab.core.mvi.CallRecord
import dev.nucleusframework.lab.core.mvi.Delivery
import dev.nucleusframework.lab.core.mvi.Reducer
import dev.nucleusframework.lab.core.mvi.plusCall
import dev.nucleusframework.lab.core.mvi.plusDelivery
import dev.nucleusframework.launcher.windows.StockIcon

/** One thumbnail toolbar button as the probe configures it. */
@Immutable
data class ThumbButtonSpec(
    val id: Int,
    val tooltip: String,
    val icon: StockIcon,
    val enabled: Boolean = true,
    val hidden: Boolean = false,
)

val DefaultThumbButtons: List<ThumbButtonSpec> =
    listOf(
        ThumbButtonSpec(0, "Back", StockIcon.FOLDER_BACK),
        ThumbButtonSpec(1, "Find", StockIcon.FIND),
        ThumbButtonSpec(2, "Share", StockIcon.SHARE),
        ThumbButtonSpec(3, "Settings", StockIcon.SETTINGS),
    )

/** Overlay icons offered by the probe; each is a stock shell icon, so no file is involved. */
val OverlayChoices: List<StockIcon> =
    listOf(StockIcon.INFO, StockIcon.WARNING, StockIcon.ERROR, StockIcon.SHIELD, StockIcon.LOCK)

@Immutable
data class WindowsTaskbarState(
    val jumpList: Availability = Availability.Unknown,
    val taskbar: Availability = Availability.Unknown,
    val hwnd: Long = 0,
    /** Titles of the tasks the jump list was last committed with; `null` once cleared. */
    val jumpListTasks: List<String>? = null,
    /** Jump list tasks that came back to the Lab as deep links. */
    val launches: List<Delivery> = emptyList(),
    val overlay: StockIcon? = null,
    val buttons: List<ThumbButtonSpec> = DefaultThumbButtons,
    val toolbarShown: Boolean = false,
    val clicks: List<Delivery> = emptyList(),
    val calls: List<CallRecord> = emptyList(),
)

sealed interface WindowsTaskbarIntent {
    data class Attach(
        val hwnd: Long,
    ) : WindowsTaskbarIntent

    data object SetJumpList : WindowsTaskbarIntent

    data object ClearJumpList : WindowsTaskbarIntent

    data class SetOverlay(
        val icon: StockIcon,
    ) : WindowsTaskbarIntent

    data object ClearOverlay : WindowsTaskbarIntent

    data object ShowToolbar : WindowsTaskbarIntent

    data class ToggleButtonEnabled(
        val id: Int,
    ) : WindowsTaskbarIntent

    data class ToggleButtonHidden(
        val id: Int,
    ) : WindowsTaskbarIntent
}

sealed interface WindowsTaskbarEvent {
    data class Ready(
        val jumpList: Availability,
        val taskbar: Availability,
    ) : WindowsTaskbarEvent

    data class Attached(
        val hwnd: Long,
    ) : WindowsTaskbarEvent

    data class JumpListApplied(
        val tasks: List<String>?,
        val outcome: CallOutcome,
    ) : WindowsTaskbarEvent

    data class Launched(
        val delivery: Delivery,
    ) : WindowsTaskbarEvent

    data class OverlayApplied(
        val icon: StockIcon?,
        val outcome: CallOutcome,
    ) : WindowsTaskbarEvent

    data class ButtonsApplied(
        val buttons: List<ThumbButtonSpec>,
        val outcome: CallOutcome,
    ) : WindowsTaskbarEvent

    data class Clicked(
        val delivery: Delivery,
    ) : WindowsTaskbarEvent
}

object WindowsTaskbarReducer : Reducer<WindowsTaskbarState, WindowsTaskbarEvent> {
    override fun reduce(
        state: WindowsTaskbarState,
        event: WindowsTaskbarEvent,
    ): WindowsTaskbarState =
        when (event) {
            is WindowsTaskbarEvent.Ready -> state.copy(jumpList = event.jumpList, taskbar = event.taskbar)
            is WindowsTaskbarEvent.Attached -> state.copy(hwnd = event.hwnd)
            is WindowsTaskbarEvent.JumpListApplied ->
                state.copy(
                    jumpListTasks = if (event.outcome.ok) event.tasks else state.jumpListTasks,
                    calls =
                        state.calls.plusCall(
                            if (event.tasks == null) {
                                "clearJumpList()"
                            } else {
                                "setJumpList(${event.tasks.size} tasks)"
                            },
                            event.outcome,
                        ),
                )
            is WindowsTaskbarEvent.Launched -> state.copy(launches = state.launches.plusDelivery(event.delivery))
            is WindowsTaskbarEvent.OverlayApplied ->
                state.copy(
                    overlay = if (event.outcome.ok) event.icon else state.overlay,
                    calls = state.calls.plusCall(event.icon?.let { "setIcon($it)" } ?: "clearIcon()", event.outcome),
                )
            is WindowsTaskbarEvent.ButtonsApplied ->
                state.copy(
                    buttons = if (event.outcome.ok) event.buttons else state.buttons,
                    toolbarShown = state.toolbarShown || event.outcome.ok,
                    calls =
                        state.calls.plusCall(
                            if (state.toolbarShown) "updateButtons()" else "setButtons(${event.buttons.size})",
                            event.outcome,
                        ),
                )
            is WindowsTaskbarEvent.Clicked -> state.copy(clicks = state.clicks.plusDelivery(event.delivery))
        }
}
