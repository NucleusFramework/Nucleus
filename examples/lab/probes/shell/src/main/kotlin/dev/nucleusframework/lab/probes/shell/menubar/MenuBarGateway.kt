package dev.nucleusframework.lab.probes.shell.menubar

import dev.nucleusframework.lab.core.Availability
import dev.nucleusframework.menu.macos.NativePopupMenuItem
import dev.nucleusframework.menu.macos.NsMenuItemImage
import dev.nucleusframework.menu.macos.isNativePopupMenuAvailable
import dev.nucleusframework.menu.macos.popUpNativeMenu
import dev.nucleusframework.sfsymbols.SFSymbolObjectsAndTools
import dev.nucleusframework.sfsymbols.SFSymbolShapes
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject

/**
 * Port over `menu-macos`' imperative side. The menu bar itself is a composable
 * (`NativeMenuBar`), drawn by [MenuBarSession]; only the popup menu is a call.
 */
interface MenuBarGateway {
    fun availability(): Availability

    /** Pops a menu at the cursor; blocks until it closes. `true` when an item was picked. */
    fun popUp(onPick: (String) -> Unit): Boolean
}

@ContributesBinding(AppScope::class)
@Inject
class NucleusMenuBarGateway : MenuBarGateway {
    override fun availability(): Availability =
        Availability.of(isNativePopupMenuAvailable) { "nucleus_menu_macos not loaded" }

    override fun popUp(onPick: (String) -> Unit): Boolean =
        popUpNativeMenu(
            listOf(
                NativePopupMenuItem.Entry(
                    "Copy",
                    icon = NsMenuItemImage.SystemSymbol(SFSymbolObjectsAndTools.SCISSORS),
                ) {
                    onPick("Popup ▸ Copy")
                },
                NativePopupMenuItem.Entry("Favourite", icon = NsMenuItemImage.SystemSymbol(SFSymbolShapes.STAR)) {
                    onPick("Popup ▸ Favourite")
                },
                NativePopupMenuItem.Entry("Disabled", enabled = false) { onPick("Popup ▸ Disabled (must never fire)") },
                NativePopupMenuItem.Separator,
                NativePopupMenuItem.Submenu(
                    "More",
                    listOf(NativePopupMenuItem.Entry("Nested") { onPick("Popup ▸ More ▸ Nested") }),
                ),
            ),
        )
}
