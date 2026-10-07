package dev.nucleusframework.lab.probes.window.dialogs

import androidx.compose.runtime.Immutable
import dev.nucleusframework.application.NucleusWindow
import dev.nucleusframework.lab.core.mvi.Reducer
import dev.nucleusframework.lab.core.mvi.pushFront

/** The secondary surfaces the owner window can open. */
enum class Secondary(
    val label: String,
    val modal: Boolean,
    val viaLabHost: Boolean,
) {
    DecoratedDialog("DecoratedDialog", modal = true, viaLabHost = false),
    MaterialDialog("MaterialDecoratedDialog", modal = true, viaLabHost = false),
    HostedDialogDefault("HostedDialog · default host", modal = true, viaLabHost = false),
    HostedDialogLab("HostedDialog · Lab host", modal = true, viaLabHost = true),
    HostedWindowDefault("HostedWindow · default host", modal = false, viaLabHost = false),
    HostedWindowLab("HostedWindow · Lab host", modal = false, viaLabHost = true),
}

enum class FilePick { OpenFile, OpenImages, Directory, Save }

/** What a secondary surface reported from inside itself. */
@Immutable
data class SecondaryReport(
    /** Which host composed it, read from a local only the Lab host provides. */
    val composedByLabHost: Boolean,
    /** Dialog centre minus owner centre in `NucleusWindow.boundsOnScreen` units; `null` when either is unknown. */
    val centreOffsetPx: Pair<Int, Int>?,
)

@Immutable
data class PickResult(
    val epochMillis: Long,
    val pick: FilePick,
    val result: String,
    val parented: Boolean,
    val millis: Long,
    /** `false` when the dialog threw; a cancel is still a success. */
    val ok: Boolean = true,
)

@Immutable
data class DialogsState(
    val sessionOpen: Boolean = false,
    val open: Set<Secondary> = emptySet(),
    val reports: Map<Secondary, SecondaryReport> = emptyMap(),
    val hostInvocations: Int = 0,
    val ownerPresses: Int = 0,
    val ownerPressesWhileModal: Int = 0,
    val focusReturned: Boolean? = null,
    val picks: List<PickResult> = emptyList(),
) {
    val modalOpen: Boolean get() = open.any { it.modal }
}

sealed interface DialogsIntent {
    data object Open : DialogsIntent

    data object Close : DialogsIntent

    data class Show(
        val secondary: Secondary,
    ) : DialogsIntent

    data class Dismiss(
        val secondary: Secondary,
    ) : DialogsIntent

    data class Reported(
        val secondary: Secondary,
        val report: SecondaryReport,
    ) : DialogsIntent

    data object LabHostInvoked : DialogsIntent

    data object OwnerPressed : DialogsIntent

    data class Pick(
        val pick: FilePick,
    ) : DialogsIntent

    class Attached(
        val window: NucleusWindow,
    ) : DialogsIntent {
        override fun toString(): String = "Attached(owner)"
    }
}

sealed interface DialogsEvent {
    data class SessionChanged(
        val open: Boolean,
    ) : DialogsEvent

    data class Shown(
        val secondary: Secondary,
    ) : DialogsEvent

    data class Dismissed(
        val secondary: Secondary,
    ) : DialogsEvent

    data class Reported(
        val secondary: Secondary,
        val report: SecondaryReport,
    ) : DialogsEvent

    data object LabHostInvoked : DialogsEvent

    data object OwnerPressed : DialogsEvent

    data class FocusAfterClose(
        val ownerFocused: Boolean,
    ) : DialogsEvent

    data class Picked(
        val result: PickResult,
    ) : DialogsEvent
}

object DialogsReducer : Reducer<DialogsState, DialogsEvent> {
    private const val PICKS = 6

    override fun reduce(
        state: DialogsState,
        event: DialogsEvent,
    ): DialogsState =
        when (event) {
            is DialogsEvent.SessionChanged ->
                if (event.open) state.copy(sessionOpen = true) else state.copy(sessionOpen = false, open = emptySet())
            is DialogsEvent.Shown -> state.copy(open = state.open + event.secondary, focusReturned = null)
            is DialogsEvent.Dismissed -> state.copy(open = state.open - event.secondary)
            is DialogsEvent.Reported -> state.copy(reports = state.reports + (event.secondary to event.report))
            DialogsEvent.LabHostInvoked -> state.copy(hostInvocations = state.hostInvocations + 1)
            DialogsEvent.OwnerPressed ->
                state.copy(
                    ownerPresses = state.ownerPresses + 1,
                    ownerPressesWhileModal = state.ownerPressesWhileModal + if (state.modalOpen) 1 else 0,
                )
            is DialogsEvent.FocusAfterClose -> state.copy(focusReturned = event.ownerFocused)
            is DialogsEvent.Picked -> state.copy(picks = state.picks.pushFront(event.result, PICKS))
        }
}
