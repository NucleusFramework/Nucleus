@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package dev.nucleusframework.application.contextmenu

import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.ContextMenuState
import androidx.compose.foundation.text.TextContextMenu
import androidx.compose.foundation.text.TextContextMenuArea
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalLocalization
import androidx.compose.ui.platform.PlatformLocalization

/**
 * Jewel-style [TextContextMenu]: rebuilds Cut / Copy / Paste / Select All as
 * [NucleusContextMenuItem]s tagged with [ContextMenuIcon] stock values so
 * [NativeContextMenuRepresentation] can attach the OS glyphs and accelerators.
 *
 * Disabled actions are kept (same as Compose's default and Jewel) so the menu
 * shape stays stable while the field's selection changes.
 */
public object NativeTextContextMenu : TextContextMenu {
    @Composable
    override fun Area(
        textManager: TextContextMenu.TextManager,
        state: ContextMenuState,
        content: @Composable () -> Unit,
    ) {
        val localization = LocalLocalization.current
        val items = {
            nativeTextContextMenuItems(localization, textManager)
        }
        TextContextMenuArea(
            textManager = textManager,
            items = items,
            state = state,
            content = content,
        )
    }
}

internal fun nativeTextContextMenuItems(
    localization: PlatformLocalization,
    textManager: TextContextMenu.TextManager,
): List<ContextMenuItem> =
    buildList {
        textManager.cut?.let { action ->
            add(
                NucleusContextMenuItem(
                    label = localization.cut,
                    enabled = action.enabled,
                    icon = ContextMenuIcon.Cut,
                    onClick = action.execute,
                ),
            )
        }
        textManager.copy?.let { action ->
            add(
                NucleusContextMenuItem(
                    label = localization.copy,
                    enabled = action.enabled,
                    icon = ContextMenuIcon.Copy,
                    onClick = action.execute,
                ),
            )
        }
        textManager.paste?.let { action ->
            add(
                NucleusContextMenuItem(
                    label = localization.paste,
                    enabled = action.enabled,
                    icon = ContextMenuIcon.Paste,
                    onClick = action.execute,
                ),
            )
        }
        textManager.selectAll?.let { action ->
            add(
                NucleusContextMenuItem(
                    label = localization.selectAll,
                    enabled = action.enabled,
                    icon = ContextMenuIcon.SelectAll,
                    onClick = action.execute,
                ),
            )
        }
    }
