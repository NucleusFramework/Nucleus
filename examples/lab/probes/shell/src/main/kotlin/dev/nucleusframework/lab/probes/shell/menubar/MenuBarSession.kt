package dev.nucleusframework.lab.probes.shell.menubar

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import dev.nucleusframework.lab.core.mvi.stamped
import dev.nucleusframework.menu.macos.NativeKeyShortcut
import dev.nucleusframework.menu.macos.NativeMenuBar
import dev.nucleusframework.menu.macos.NsMenuItemBadge
import dev.nucleusframework.menu.macos.NsMenuItemImage
import dev.nucleusframework.sfsymbols.SFSymbolGeneral
import dev.nucleusframework.sfsymbols.SFSymbolObjectsAndTools
import dev.nucleusframework.sfsymbols.SFSymbolStatus

/**
 * The Lab's menu bar, composed as a session at the application root so it stays installed
 * while the tester browses other probes, the way an app's menu bar is global.
 */
@Composable
fun MenuBarSession(vm: MenuBarViewModel) {
    val state by vm.state.collectAsState()
    DisposableEffect(Unit) {
        vm.onIntent(MenuBarIntent.Installed(true))
        onDispose { vm.onIntent(MenuBarIntent.Installed(false)) }
    }

    fun pick(action: MenuAction) = vm.onIntent(MenuBarIntent.Picked(action.stamped()))

    NativeMenuBar {
        Menu("Lab") {
            Item(
                "Ping",
                shortcut = NativeKeyShortcut("p", shift = true),
                icon = NsMenuItemImage.SystemSymbol(SFSymbolStatus.INFO_CIRCLE),
                subtitle = "⌘⇧P",
                onClick = { pick(MenuAction.Ping) },
            )
            CheckboxItem("Checkable", checked = state.checkable, onCheckedChange = { pick(MenuAction.ToggleCheckable) })
            Item(
                "Inbox",
                badge = NsMenuItemBadge.Count(state.inboxBadge),
                icon = NsMenuItemImage.SystemSymbol(SFSymbolGeneral.TRAY_FILL),
                onClick = { pick(MenuAction.BumpBadge) },
            )
            Item(
                "Disabled item",
                enabled = false,
                onClick = { pick(MenuAction.Popup("Disabled item (must never fire)")) },
            )
            Separator()
            SectionHeader("Mode")
            MenuMode.entries.forEach { mode ->
                RadioButtonItem(
                    mode.name,
                    selected = state.mode == mode,
                    onClick = { pick(MenuAction.SelectMode(mode)) },
                )
            }
            Separator()
            Menu("More", icon = NsMenuItemImage.SystemSymbol(SFSymbolObjectsAndTools.FOLDER)) {
                Item("Nested item", toolTip = "A tooltip on a nested item", onClick = { pick(MenuAction.Nested) })
            }
        }
        MenuWindow("Window")
        MenuHelp("Help") {
            SearchField(placeholder = "Search")
        }
    }
}
