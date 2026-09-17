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
import dev.nucleusframework.window.tao.NativePopupLayers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * OS-looking context menu: `NSMenu` on macOS, a Compose Fluent flyout on
 * Windows, a Compose Adwaita flyout on GTK Linux desktops (GNOME, XFCE,
 * Cinnamon, MATE, …), a Compose Breeze flyout on Qt Linux desktops (KDE
 * Plasma, LXQt, Deepin, …).
 *
 * The Compose flyouts open in a native popup surface whatever the window's
 * `nativePopupLayers` flag says ([NativePopupLayers]): an OS-looking menu has
 * to be able to leave the window, like the menus it imitates, and the
 * application's choice for its own popups must not decide that.
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
            Platform.Windows ->
                NativePopupLayers { ContextMenuFlyout(status, entries, FluentMenuTheme, onDismiss) }
            Platform.Linux ->
                NativePopupLayers { ContextMenuFlyout(status, entries, linuxContextMenuTheme(), onDismiss) }
            Platform.MacOS -> {
                val macEntries = entries.map { it.toMacPopupItem() }
                LaunchedEffect(status) {
                    try {
                        // `popUpMenuPositioningItem:` spins AppKit's nested
                        // event-tracking loop. Entered from this dispatcher it
                        // would run *inside* tao's event callback, whose
                        // reentrancy guard (`AppState::in_callback`) then
                        // suppresses every `MainEventsCleared` tick until the
                        // menu closes — freezing frames, animations, `delay`
                        // and the whole `Dispatchers.Main` pump for as long as
                        // the menu is open. Hop off the UI thread instead: the
                        // menu bridge marshals onto the AppKit main queue
                        // itself, so the menu still opens on the main thread
                        // but from a regular queue drain, outside the callback
                        // — the tracking loop then runs in a common run-loop
                        // mode where tao's observers keep firing. Same
                        // discipline as the deferred window-drag helper in
                        // `window_drag.m`.
                        withContext(Dispatchers.IO) {
                            popUpNativeMenu(macEntries)
                        }
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
