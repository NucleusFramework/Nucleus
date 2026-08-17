@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package dev.nucleusframework.application.contextmenu

import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.ContextMenuRepresentation
import androidx.compose.foundation.ContextMenuState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import dev.nucleusframework.core.runtime.LinuxUiToolkit
import dev.nucleusframework.core.runtime.Platform
import dev.nucleusframework.menu.macos.NativePopupMenuItem
import dev.nucleusframework.menu.macos.NsMenuItemImage
import dev.nucleusframework.menu.macos.popUpNativeMenu

/**
 * OS-looking context menu: `NSMenu` on macOS, a Compose Fluent flyout on
 * Windows, a Compose Adwaita flyout on GTK Linux desktops (GNOME, XFCE,
 * Cinnamon, MATE, …), a Compose Breeze flyout on Qt Linux desktops (KDE
 * Plasma, LXQt, Deepin, …).
 *
 * Calling [Representation] off a supported OS closes the menu immediately
 * so a stray install cannot leave Compose in `Open`.
 */
public object NativeContextMenuRepresentation : ContextMenuRepresentation {
    @Composable
    override fun Representation(
        state: ContextMenuState,
        items: () -> List<ContextMenuItem>,
    ) {
        val status = state.status
        if (status !is ContextMenuState.Status.Open) return
        val interpreter = LocalContextMenuItemInterpreter.current
        val separator = LocalContextMenuDivider.current ?: NucleusContextMenuDivider
        val entries =
            items()
                .map { item -> interpreter.interpret(item, separator) }
                .withNormalizedSeparators()
        val onDismiss = { state.status = ContextMenuState.Status.Closed }
        if (entries.isEmpty()) {
            LaunchedEffect(status) { onDismiss() }
            return
        }
        when (Platform.Current) {
            Platform.Windows -> ContextMenuFlyout(status, entries, FluentMenuTheme, onDismiss)
            Platform.Linux -> ContextMenuFlyout(status, entries, linuxContextMenuTheme(), onDismiss)
            Platform.MacOS -> {
                LaunchedEffect(status) {
                    try {
                        popUpNativeMenu(entries.map { it.toMacPopupItem() })
                    } finally {
                        onDismiss()
                    }
                }
            }
            else -> LaunchedEffect(status) { onDismiss() }
        }
    }
}

internal fun linuxContextMenuTheme(): ContextMenuFlyoutTheme =
    when (LinuxUiToolkit.Current) {
        LinuxUiToolkit.Qt -> BreezeMenuTheme
        LinuxUiToolkit.Gtk -> AdwaitaMenuTheme
    }

internal fun ContextMenuEntry.toMacPopupItem(): NativePopupMenuItem =
    when (this) {
        is ContextMenuEntry.Separator -> NativePopupMenuItem.Separator
        is ContextMenuEntry.Submenu ->
            NativePopupMenuItem.Submenu(
                title = label,
                items = items.map { child -> child.toMacPopupItem() },
            )
        is ContextMenuEntry.Item ->
            NativePopupMenuItem.Entry(
                title = label,
                enabled = enabled,
                icon = icon?.toNsMenuItemImage(),
                onClick = onClick,
            )
    }

internal fun ContextMenuIcon.toNsMenuItemImage(): NsMenuItemImage? {
    val symbol =
        when (this) {
            ContextMenuIcon.Cut -> "scissors"
            ContextMenuIcon.Copy -> "doc.on.doc"
            ContextMenuIcon.Paste -> "doc.on.clipboard"
            ContextMenuIcon.SelectAll -> return null
            ContextMenuIcon.Delete -> "trash"
            ContextMenuIcon.Folder -> "folder"
            is ContextMenuIcon.SfSymbol -> name
        }
    return NsMenuItemImage.SystemSymbol(symbol)
}
