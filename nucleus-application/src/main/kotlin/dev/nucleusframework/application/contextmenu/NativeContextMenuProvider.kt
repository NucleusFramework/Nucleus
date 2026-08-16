@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package dev.nucleusframework.application.contextmenu

import androidx.compose.foundation.LocalContextMenuRepresentation
import androidx.compose.foundation.text.LocalTextContextMenu
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf
import dev.nucleusframework.core.runtime.Platform
import dev.nucleusframework.menu.macos.isNativePopupMenuAvailable

/**
 * `true` when [NativeContextMenuProvider] has installed the native
 * representation in this subtree. Spellcheck uses this to stop drawing its
 * own Compose popup (separators are native rows instead).
 */
public val LocalNativeContextMenu: ProvidableCompositionLocal<Boolean> =
    staticCompositionLocalOf { false }

/**
 * Whether this process can show an AppKit context menu: macOS + `menu-macos`
 * native library loaded. Windows and Linux are always `false`.
 */
public val isNativeContextMenuSupported: Boolean
    get() = Platform.Current == Platform.MacOS && isNativePopupMenuAvailable

/**
 * Jewel-style context-menu provider: installs [NativeTextContextMenu] (so
 * Cut / Copy / Paste carry [ContextMenuIcon] stock tags) and
 * [NativeContextMenuRepresentation] (so the menu is an `NSMenu`).
 *
 * No-op when [enabled] is `false` or when [isNativeContextMenuSupported] is
 * `false` (Windows, Linux, missing native lib). Compose / Jewel chrome stays.
 *
 * @param enabled Caller opt-in, typically [DecoratedWindow]'s
 *   `nativeContextMenu` flag.
 * @param content Window content that should use the native menu.
 */
@Suppress("FunctionNaming")
@Composable
public fun NativeContextMenuProvider(
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    if (!enabled || !isNativeContextMenuSupported) {
        content()
        return
    }
    CompositionLocalProvider(
        LocalNativeContextMenu provides true,
        LocalContextMenuRepresentation provides NativeContextMenuRepresentation,
        LocalTextContextMenu provides NativeTextContextMenu,
        content = content,
    )
}
