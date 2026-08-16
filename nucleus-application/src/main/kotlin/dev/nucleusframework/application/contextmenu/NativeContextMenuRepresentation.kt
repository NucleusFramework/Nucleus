@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package dev.nucleusframework.application.contextmenu

import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.ContextMenuRepresentation
import androidx.compose.foundation.ContextMenuState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import dev.nucleusframework.application.spellcheck.LocalSpellcheckMenuSeparator
import dev.nucleusframework.core.runtime.Platform
import dev.nucleusframework.menu.macos.NativePopupMenuItem
import dev.nucleusframework.menu.macos.NsMenuItemImage
import dev.nucleusframework.menu.macos.popUpNativeMenu

/**
 * Renders the context menu as a native `NSMenu` on macOS, or as a Compose
 * Fluent flyout (WinUI 3 MenuFlyout metrics) on Windows.
 *
 * On Linux this object is never installed ([NativeContextMenuProvider] is a
 * no-op there). Calling [Representation] off a supported OS closes the menu
 * immediately so a stray install cannot leave Compose in `Open`.
 */
public object NativeContextMenuRepresentation : ContextMenuRepresentation {
    @Composable
    override fun Representation(
        state: ContextMenuState,
        items: () -> List<ContextMenuItem>,
    ) {
        val status = state.status
        if (status !is ContextMenuState.Status.Open) return
        if (Platform.Current == Platform.Windows) {
            FluentContextMenuPopup(state, items)
            return
        }
        val interpreter = LocalContextMenuItemInterpreter.current
        val separator = LocalSpellcheckMenuSeparator.current
        LaunchedEffect(status) {
            val resolved = items()
            if (resolved.isEmpty() || !isNativeContextMenuSupported) {
                state.status = ContextMenuState.Status.Closed
                return@LaunchedEffect
            }
            val popupItems =
                resolved.map { item ->
                    interpreter.interpret(item, separator).toMacPopupItem()
                }
            try {
                popUpNativeMenu(popupItems)
            } finally {
                state.status = ContextMenuState.Status.Closed
            }
        }
    }
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
