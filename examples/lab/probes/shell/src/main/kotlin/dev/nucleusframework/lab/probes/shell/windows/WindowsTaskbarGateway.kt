package dev.nucleusframework.lab.probes.shell.windows

import dev.nucleusframework.lab.core.Availability
import dev.nucleusframework.lab.core.mvi.CallOutcome
import dev.nucleusframework.lab.core.mvi.Stamped
import dev.nucleusframework.lab.core.mvi.stamped
import dev.nucleusframework.lab.probes.shell.common.LauncherIdentity
import dev.nucleusframework.launcher.windows.JumpListCategory
import dev.nucleusframework.launcher.windows.JumpListItem
import dev.nucleusframework.launcher.windows.KnownCategory
import dev.nucleusframework.launcher.windows.TaskbarIconSource
import dev.nucleusframework.launcher.windows.ThumbnailToolbarButton
import dev.nucleusframework.launcher.windows.WindowsJumpListManager
import dev.nucleusframework.launcher.windows.WindowsOverlayIcon
import dev.nucleusframework.launcher.windows.WindowsThumbnailToolbar
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** Port over `launcher-windows`' jump list, overlay icon and thumbnail toolbar. */
interface WindowsTaskbarGateway {
    fun jumpListAvailability(): Availability

    fun taskbarAvailability(): Availability

    fun setJumpList(
        tasks: List<JumpListItem>,
        categories: List<JumpListCategory>,
        known: List<KnownCategory>,
    ): CallOutcome

    fun clearJumpList(): CallOutcome

    fun setOverlay(
        hwnd: Long,
        icon: TaskbarIconSource,
        description: String,
    ): CallOutcome

    fun clearOverlay(hwnd: Long): CallOutcome

    /** First call adds the buttons (Windows allows that once per window), later calls update them. */
    fun showButtons(
        hwnd: Long,
        buttons: List<ThumbnailToolbarButton>,
    ): CallOutcome

    /** Button ids clicked under the window's thumbnail, stamped where the click was delivered. */
    val clicks: SharedFlow<Stamped<Int>>
}

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
class NucleusWindowsTaskbarGateway : WindowsTaskbarGateway {
    private val clickFlow = MutableSharedFlow<Stamped<Int>>(extraBufferCapacity = 32)
    override val clicks: SharedFlow<Stamped<Int>> = clickFlow.asSharedFlow()

    /** HWNDs whose toolbar was added: Windows rejects a second ThumbBarAddButtons. */
    private val toolbarAdded = mutableSetOf<Long>()

    override fun jumpListAvailability(): Availability =
        LauncherIdentity.windowsLauncher(WindowsJumpListManager.isAvailable)

    override fun taskbarAvailability(): Availability = LauncherIdentity.windowsLauncher(WindowsOverlayIcon.isAvailable)

    override fun setJumpList(
        tasks: List<JumpListItem>,
        categories: List<JumpListCategory>,
        known: List<KnownCategory>,
    ): CallOutcome =
        CallOutcome.of(
            WindowsJumpListManager.setJumpList(categories, tasks, known),
        ) { WindowsJumpListManager.lastError }

    override fun clearJumpList(): CallOutcome =
        CallOutcome.of(WindowsJumpListManager.clearJumpList()) { WindowsJumpListManager.lastError }

    override fun setOverlay(
        hwnd: Long,
        icon: TaskbarIconSource,
        description: String,
    ): CallOutcome =
        CallOutcome.of(WindowsOverlayIcon.setIcon(hwnd, icon, description)) { WindowsOverlayIcon.lastError }

    override fun clearOverlay(hwnd: Long): CallOutcome =
        CallOutcome.of(WindowsOverlayIcon.clearIcon(hwnd)) { WindowsOverlayIcon.lastError }

    override fun showButtons(
        hwnd: Long,
        buttons: List<ThumbnailToolbarButton>,
    ): CallOutcome {
        if (hwnd in toolbarAdded) {
            return CallOutcome.of(
                WindowsThumbnailToolbar.updateButtons(hwnd, buttons),
            ) { WindowsThumbnailToolbar.lastError }
        }
        val ok = WindowsThumbnailToolbar.setButtons(hwnd, buttons) { id -> clickFlow.tryEmit(id.stamped()) }
        if (ok) toolbarAdded += hwnd
        return CallOutcome.of(ok) { WindowsThumbnailToolbar.lastError }
    }
}
