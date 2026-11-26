package dev.nucleusframework.lab.probes.shell.taskbar

import dev.nucleusframework.application.NucleusWindow
import dev.nucleusframework.core.runtime.Platform
import dev.nucleusframework.lab.core.Availability
import dev.nucleusframework.lab.core.mvi.CallOutcome
import dev.nucleusframework.taskbarprogress.TaskbarProgress
import dev.nucleusframework.taskbarprogress.tao.hideTaskbarProgress
import dev.nucleusframework.taskbarprogress.tao.requestTaskbarAttention
import dev.nucleusframework.taskbarprogress.tao.setTaskbarProgress
import dev.nucleusframework.taskbarprogress.tao.setTaskbarState
import dev.nucleusframework.taskbarprogress.tao.stopTaskbarAttention
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject

/** Port over `taskbar-progress` + its Nucleus window bridge (`taskbar-progress-tao`). */
interface TaskbarGateway {
    fun availability(): Availability

    /** Where the bar is drawn, for the readout. */
    fun surface(): String

    fun setState(
        window: NucleusWindow,
        state: TaskbarProgress.State,
    ): CallOutcome

    fun setProgress(
        window: NucleusWindow,
        value: Double,
    ): CallOutcome

    fun hide(window: NucleusWindow): CallOutcome

    fun requestAttention(
        window: NucleusWindow,
        type: TaskbarProgress.AttentionType,
    ): CallOutcome

    fun stopAttention(window: NucleusWindow): CallOutcome
}

@ContributesBinding(AppScope::class)
@Inject
class NucleusTaskbarGateway : TaskbarGateway {
    override fun availability(): Availability =
        Availability.of(TaskbarProgress.isAvailable()) {
            when (Platform.Current) {
                Platform.Linux ->
                    "no launcher entry: needs a .desktop file and a Unity-API dock " +
                        "(Dash to Dock, Plank, KDE)"
                else -> "native taskbar bridge not loaded"
            }
        }

    override fun surface(): String =
        when (Platform.Current) {
            Platform.Windows -> "ITaskbarList3 on the window's taskbar button"
            Platform.MacOS -> "NSDockTile progress bar on the app's Dock icon"
            Platform.Linux -> "LauncherEntry progress via D-Bus (launcher-linux)"
            else -> "none"
        }

    override fun setState(
        window: NucleusWindow,
        state: TaskbarProgress.State,
    ): CallOutcome = outcome(window.setTaskbarState(state))

    override fun setProgress(
        window: NucleusWindow,
        value: Double,
    ): CallOutcome = outcome(window.setTaskbarProgress(value))

    override fun hide(window: NucleusWindow): CallOutcome = outcome(window.hideTaskbarProgress())

    override fun requestAttention(
        window: NucleusWindow,
        type: TaskbarProgress.AttentionType,
    ): CallOutcome = outcome(window.requestTaskbarAttention(type))

    override fun stopAttention(window: NucleusWindow): CallOutcome = outcome(window.stopTaskbarAttention())

    private fun outcome(ok: Boolean) = CallOutcome.of(ok) { "returned false (no native handle, or the OS refused)" }
}
