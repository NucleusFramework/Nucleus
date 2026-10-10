package dev.nucleusframework.lab.probes.shell.windows

import androidx.lifecycle.ViewModel
import dev.nucleusframework.lab.core.ProbeId
import dev.nucleusframework.lab.core.commands.LabCommands
import dev.nucleusframework.lab.core.mvi.MviViewModel
import dev.nucleusframework.lab.core.mvi.map
import dev.nucleusframework.lab.core.mvi.toDelivery
import dev.nucleusframework.lab.core.mvi.unjudgedDelivery
import dev.nucleusframework.lab.core.timeline.Severity
import dev.nucleusframework.lab.core.timeline.Timeline
import dev.nucleusframework.launcher.windows.JumpListCategory
import dev.nucleusframework.launcher.windows.JumpListItem
import dev.nucleusframework.launcher.windows.KnownCategory
import dev.nucleusframework.launcher.windows.StockIcon
import dev.nucleusframework.launcher.windows.TaskbarIconSource
import dev.nucleusframework.launcher.windows.ThumbnailToolbarButton
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey

@ViewModelKey
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
@Inject
class WindowsTaskbarViewModel(
    private val gateway: WindowsTaskbarGateway,
    commands: LabCommands,
    timeline: Timeline,
) : MviViewModel<WindowsTaskbarState, WindowsTaskbarIntent, WindowsTaskbarEvent, Nothing>(
        WindowsTaskbarState(),
        WindowsTaskbarReducer,
        timeline,
        WindowsTaskbarProbe.ID,
    ) {
    init {
        dispatch(WindowsTaskbarEvent.Ready(gateway.jumpListAvailability(), gateway.taskbarAvailability()))
        // A jump list task re-enters the Lab as nucleus-lab://probe/shell.windows-taskbar?jump=<name>.
        onParams(commands) { params ->
            params["jump"]?.let { name -> dispatch(WindowsTaskbarEvent.Launched(unjudgedDelivery("task '$name'"))) }
        }
        launch {
            gateway.clicks.collect { stamped ->
                val label =
                    state.value.buttons
                        .firstOrNull { it.id == stamped.value }
                        ?.tooltip ?: "#${stamped.value}"
                val delivery = stamped.map { "button '$label'" }.toDelivery()
                dispatch(stamped.map { WindowsTaskbarEvent.Clicked(delivery) })
            }
        }
    }

    override suspend fun handle(intent: WindowsTaskbarIntent) {
        val hwnd = state.value.hwnd
        when (intent) {
            is WindowsTaskbarIntent.Attach ->
                if (intent.hwnd != hwnd) {
                    dispatch(WindowsTaskbarEvent.Attached(intent.hwnd))
                }
            WindowsTaskbarIntent.SetJumpList -> {
                val outcome = gateway.setJumpList(JumpTasks, JumpCategories, listOf(KnownCategory.RECENT))
                dispatch(
                    WindowsTaskbarEvent.JumpListApplied(
                        JumpTasks
                            .filterNot {
                                it.isSeparator
                            }.map { it.title },
                        outcome,
                    ),
                    severity(outcome.ok),
                )
            }
            WindowsTaskbarIntent.ClearJumpList -> {
                val outcome = gateway.clearJumpList()
                dispatch(WindowsTaskbarEvent.JumpListApplied(null, outcome), severity(outcome.ok))
            }
            is WindowsTaskbarIntent.SetOverlay -> {
                val outcome =
                    gateway.setOverlay(
                        hwnd,
                        TaskbarIconSource.FromStock(intent.icon),
                        "Lab overlay: ${intent.icon}",
                    )
                dispatch(WindowsTaskbarEvent.OverlayApplied(intent.icon, outcome), severity(outcome.ok))
            }
            WindowsTaskbarIntent.ClearOverlay -> {
                val outcome = gateway.clearOverlay(hwnd)
                dispatch(WindowsTaskbarEvent.OverlayApplied(null, outcome), severity(outcome.ok))
            }
            WindowsTaskbarIntent.ShowToolbar -> applyButtons(state.value.buttons)
            is WindowsTaskbarIntent.ToggleButtonEnabled ->
                applyButtons(state.value.buttons.map { if (it.id == intent.id) it.copy(enabled = !it.enabled) else it })
            is WindowsTaskbarIntent.ToggleButtonHidden ->
                applyButtons(state.value.buttons.map { if (it.id == intent.id) it.copy(hidden = !it.hidden) else it })
        }
    }

    private fun applyButtons(specs: List<ThumbButtonSpec>) {
        val buttons =
            specs.map {
                ThumbnailToolbarButton(
                    id = it.id,
                    tooltip = it.tooltip,
                    icon = TaskbarIconSource.FromStock(it.icon),
                    enabled = it.enabled,
                    hidden = it.hidden,
                )
            }
        val outcome = gateway.showButtons(state.value.hwnd, buttons)
        dispatch(WindowsTaskbarEvent.ButtonsApplied(specs, outcome), severity(outcome.ok))
    }

    private fun severity(ok: Boolean) = if (ok) Severity.Info else Severity.Error

    companion object {
        val JumpTasks: List<JumpListItem> =
            listOf(
                JumpListItem(
                    "Ping this probe",
                    LabCommands.deepLink(WindowsTaskbarProbe.ID, mapOf("jump" to "ping")),
                    "Comes back as a deep link",
                    TaskbarIconSource.FromStock(StockIcon.INFO),
                ),
                JumpListItem(
                    "Ping with spaces & accents",
                    LabCommands.deepLink(WindowsTaskbarProbe.ID, mapOf("jump" to "élan vital")),
                    "Argument escaping",
                    TaskbarIconSource.FromStock(StockIcon.RENAME),
                ),
                JumpListItem.SEPARATOR,
                JumpListItem(
                    "Badge → 9",
                    LabCommands.deepLink(ProbeId("shell.badge"), mapOf("count" to "9")),
                    "Cross-probe command",
                    TaskbarIconSource.FromStock(StockIcon.STACK),
                ),
            )

        val JumpCategories: List<JumpListCategory> =
            listOf(
                JumpListCategory(
                    "Lab",
                    listOf(
                        JumpListItem(
                            "Overview",
                            LabCommands.deepLink(ProbeId("lab.overview")),
                            "Open the Lab overview",
                        ),
                        JumpListItem(
                            "Environment",
                            LabCommands.deepLink(ProbeId("lab.environment")),
                            "Open the environment readout",
                        ),
                    ),
                ),
            )
    }
}
