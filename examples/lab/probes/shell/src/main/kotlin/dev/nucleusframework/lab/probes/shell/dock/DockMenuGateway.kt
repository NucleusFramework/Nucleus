package dev.nucleusframework.lab.probes.shell.dock

import dev.nucleusframework.lab.core.Availability
import dev.nucleusframework.lab.core.mvi.Stamped
import dev.nucleusframework.lab.core.mvi.stamped
import dev.nucleusframework.launcher.macos.DockMenuItem
import dev.nucleusframework.launcher.macos.DockMenuListener
import dev.nucleusframework.launcher.macos.MacOsDockMenu
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** Port over `launcher-macos`' Dock menu (right-click / long-press on the Dock icon). */
interface DockMenuGateway {
    fun availability(): Availability

    fun setMenu(items: List<DockMenuItem>)

    fun clear()

    /** Item ids picked in the Dock menu, stamped on the thread the listener ran on. */
    val clicks: SharedFlow<Stamped<Int>>
}

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
class NucleusDockMenuGateway : DockMenuGateway {
    private val clickFlow = MutableSharedFlow<Stamped<Int>>(extraBufferCapacity = 32)
    override val clicks: SharedFlow<Stamped<Int>> = clickFlow.asSharedFlow()

    override fun availability(): Availability =
        Availability.of(MacOsDockMenu.isAvailable) { "nucleus_launcher_macos not loaded" }

    override fun setMenu(items: List<DockMenuItem>) {
        // The listener is process-wide; owning it here keeps one subscriber whatever the screen does.
        MacOsDockMenu.listener = DockMenuListener { id -> clickFlow.tryEmit(id.stamped()) }
        MacOsDockMenu.setDockMenu(items)
    }

    override fun clear() {
        MacOsDockMenu.clearDockMenu()
    }
}
