package dev.nucleusframework.lab.probes.shell.windows

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import dev.nucleusframework.application.LocalNucleusWindow
import dev.nucleusframework.core.runtime.ExecutableRuntime
import dev.nucleusframework.core.runtime.Platform
import dev.nucleusframework.lab.core.Availability
import dev.nucleusframework.lab.core.Capability
import dev.nucleusframework.lab.core.Check
import dev.nucleusframework.lab.core.Domain
import dev.nucleusframework.lab.core.Probe
import dev.nucleusframework.lab.core.ProbeDescriptor
import dev.nucleusframework.lab.core.ProbeId
import dev.nucleusframework.lab.designsystem.Actions
import dev.nucleusframework.lab.designsystem.EventLog
import dev.nucleusframework.lab.designsystem.Hint
import dev.nucleusframework.lab.designsystem.PrimaryAction
import dev.nucleusframework.lab.designsystem.ProbeLayout
import dev.nucleusframework.lab.designsystem.Readout
import dev.nucleusframework.lab.designsystem.SecondaryAction
import dev.nucleusframework.lab.designsystem.SubHeading
import dev.nucleusframework.lab.designsystem.ToggleChipsRow
import dev.nucleusframework.lab.designsystem.toLogEntry
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metrox.viewmodel.metroViewModel

@ContributesIntoSet(AppScope::class)
@Inject
class WindowsTaskbarProbe : Probe {
    override val descriptor =
        ProbeDescriptor(
            id = ID,
            title = "Jump list, overlay & thumbnail toolbar",
            domain = Domain.Shell,
            summary =
                "Do the taskbar's jump list, overlay badge and thumbnail buttons appear and " +
                    "call back into the app?",
            modules = listOf("launcher-windows"),
            platforms = setOf(Platform.Windows),
            checks =
                listOf(
                    Check(
                        "jumplist",
                        "Right-clicking the taskbar button shows the Lab category, the tasks and a separator",
                    ),
                    Check(
                        "jump-launch",
                        "Clicking “Ping this probe” lands here as a task launch (packaged app, single instance)",
                    ),
                    Check("jump-escape", "“Ping with spaces & accents” arrives as “élan vital”, intact"),
                    Check("overlay", "Each overlay icon appears at the corner of the taskbar button; Clear removes it"),
                    Check(
                        "thumb-buttons",
                        "Hovering the taskbar button shows the 4 buttons under the thumbnail, with tooltips",
                    ),
                    Check(
                        "thumb-clicks",
                        "Each click is listed once, on the UI thread; disabled buttons cannot be clicked",
                    ),
                    Check("thumb-hidden", "Hiding a button removes it from the toolbar without moving the others' ids"),
                ),
            keywords = listOf("ICustomDestinationList", "ITaskbarList3", "taskbar", "overlay", "thumbbar"),
        )

    @Composable
    override fun Content() {
        val vm = metroViewModel<WindowsTaskbarViewModel>()
        val state by vm.state.collectAsState()
        val hwnd =
            LocalNucleusWindow.current.unsafe.taoWindow
                ?.nativeHandle ?: 0L
        LaunchedEffect(hwnd) { vm.onIntent(WindowsTaskbarIntent.Attach(hwnd)) }
        val windowReady = state.taskbar.isAvailable && state.hwnd != 0L

        ProbeLayout(
            capabilities =
                listOf(
                    Capability(
                        "Jump list",
                        state.jumpList,
                        detail =
                            "dev run: tasks relaunch the executable, not this Gradle run"
                                .takeIf { ExecutableRuntime.isDev() },
                    ),
                    Capability("Taskbar button", state.taskbar),
                    Capability(
                        "HWND",
                        if (state.hwnd != 0L) {
                            Availability.Available
                        } else {
                            Availability.Unavailable("window not realized")
                        },
                    ),
                ),
            controls = {
                SubHeading("Jump list")
                Actions {
                    PrimaryAction(
                        "Set jump list",
                        enabled = state.jumpList.isAvailable,
                    ) { vm.onIntent(WindowsTaskbarIntent.SetJumpList) }
                    SecondaryAction(
                        "Clear",
                        enabled = state.jumpList.isAvailable,
                    ) { vm.onIntent(WindowsTaskbarIntent.ClearJumpList) }
                }
                SubHeading("Overlay icon")
                Actions {
                    OverlayChoices.forEach { icon ->
                        SecondaryAction(
                            icon.name.lowercase(),
                            enabled = windowReady,
                        ) { vm.onIntent(WindowsTaskbarIntent.SetOverlay(icon)) }
                    }
                    SecondaryAction("Clear", enabled = windowReady) { vm.onIntent(WindowsTaskbarIntent.ClearOverlay) }
                }
                SubHeading("Thumbnail toolbar")
                Actions {
                    PrimaryAction(
                        if (state.toolbarShown) "Re-apply buttons" else "Add buttons",
                        enabled = windowReady,
                    ) {
                        vm.onIntent(WindowsTaskbarIntent.ShowToolbar)
                    }
                }
                if (state.toolbarShown) {
                    state.buttons.forEach { button ->
                        ToggleChipsRow(
                            button.tooltip,
                            listOf("enabled" to button.enabled, "visible" to !button.hidden),
                        ) { index, _ ->
                            vm.onIntent(
                                if (index == 0) {
                                    WindowsTaskbarIntent.ToggleButtonEnabled(button.id)
                                } else {
                                    WindowsTaskbarIntent.ToggleButtonHidden(button.id)
                                },
                            )
                        }
                    }
                } else {
                    Hint("Add the buttons to toggle them one by one.")
                }
            },
            observed = {
                Readout("HWND", "0x%X".format(state.hwnd))
                Readout("Jump list tasks", state.jumpListTasks?.joinToString() ?: "none committed")
                Readout("Overlay", state.overlay?.name ?: "none")
                SubHeading("Task launches (deep links)")
                EventLog(state.launches.map { it.toLogEntry() }, empty = "No jump list task came back yet.")
                SubHeading("Thumbnail clicks")
                EventLog(state.clicks.map { it.toLogEntry() }, empty = "No button clicked yet.")
                SubHeading("Calls")
                EventLog(state.calls.map { it.toLogEntry() }, empty = "No call made yet.")
            },
        )
    }

    companion object {
        val ID = ProbeId("shell.windows-taskbar")
    }
}
