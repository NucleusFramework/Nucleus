package dev.nucleusframework.lab.probes.shell.linux

import dev.nucleusframework.freedesktop.icons.FreedesktopIcon
import dev.nucleusframework.lab.core.Availability
import dev.nucleusframework.lab.core.mvi.CallOutcome
import dev.nucleusframework.lab.core.mvi.Stamped
import dev.nucleusframework.lab.core.mvi.stamped
import dev.nucleusframework.lab.probes.shell.common.LauncherIdentity
import dev.nucleusframework.launcher.linux.DbusmenuItem
import dev.nucleusframework.launcher.linux.LauncherProperties
import dev.nucleusframework.launcher.linux.LinuxLauncherEntry
import dev.nucleusframework.launcher.linux.LinuxQuicklist
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** Ids of the quicklist items the probe publishes. */
object QuicklistItems {
    const val PING = 1
    const val TOGGLE = 2
    const val SEPARATOR = 3
    const val SUBMENU = 10
    const val NESTED = 11
    const val DISABLED = 4

    fun build(toggled: Boolean): List<DbusmenuItem> =
        listOf(
            DbusmenuItem(PING, "Ping the Lab", icon = FreedesktopIcon.Status.DIALOG_INFORMATION),
            DbusmenuItem(
                TOGGLE,
                "Checkable",
                toggleType = DbusmenuItem.ToggleType.CHECKBOX,
                toggleState = if (toggled) 1 else 0,
            ),
            DbusmenuItem(DISABLED, "Disabled item", enabled = false),
            DbusmenuItem.separator(SEPARATOR),
            DbusmenuItem(SUBMENU, "More", children = listOf(DbusmenuItem(NESTED, "Nested item"))),
        )

    fun label(id: Int): String =
        when (id) {
            PING -> "Ping the Lab"
            TOGGLE -> "Checkable"
            DISABLED -> "Disabled item (must never fire)"
            NESTED -> "More ▸ Nested item"
            else -> "unknown id $id"
        }
}

/** Port over `launcher-linux`: the Unity LauncherEntry signal and its dbusmenu quicklist. */
interface LinuxLauncherGateway {
    fun availability(): Availability

    /** The `.desktop` id the launcher entry is keyed on, as detected for this process. */
    fun detectedDesktopFile(): String?

    fun registerQueryHandler(desktopFile: String): CallOutcome

    fun update(
        desktopFile: String,
        properties: LauncherProperties,
    ): CallOutcome

    fun publishQuicklist(
        desktopFile: String,
        items: List<DbusmenuItem>,
    ): CallOutcome

    fun removeQuicklist(desktopFile: String): CallOutcome

    val clicks: SharedFlow<Stamped<Int>>
}

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
class NucleusLinuxLauncherGateway : LinuxLauncherGateway {
    private val clickFlow = MutableSharedFlow<Stamped<Int>>(extraBufferCapacity = 32)
    override val clicks: SharedFlow<Stamped<Int>> = clickFlow.asSharedFlow()

    private val quicklist =
        LinuxQuicklist("/dev/nucleusframework/lab/Quicklist").apply {
            listener = LinuxQuicklist.Listener { id -> clickFlow.tryEmit(id.stamped()) }
        }

    override fun availability(): Availability = LauncherIdentity.linuxLauncherLibrary()

    override fun detectedDesktopFile(): String? = LauncherIdentity.linuxDesktopFile

    override fun registerQueryHandler(desktopFile: String): CallOutcome =
        CallOutcome.of(LinuxLauncherEntry.registerQueryHandler(LauncherIdentity.linuxAppUri(desktopFile))) {
            "Query handler not exported on the session bus"
        }

    override fun update(
        desktopFile: String,
        properties: LauncherProperties,
    ): CallOutcome =
        CallOutcome.of(LinuxLauncherEntry.update(LauncherIdentity.linuxAppUri(desktopFile), properties)) {
            "Update signal not emitted"
        }

    override fun publishQuicklist(
        desktopFile: String,
        items: List<DbusmenuItem>,
    ): CallOutcome {
        if (!quicklist.setMenu(items)) return CallOutcome(false, "dbusmenu object not exported")
        return update(desktopFile, LauncherProperties(quicklist = quicklist.objectPath))
    }

    override fun removeQuicklist(desktopFile: String): CallOutcome {
        val outcome = update(desktopFile, LauncherProperties(quicklist = ""))
        quicklist.dispose()
        return outcome
    }
}
