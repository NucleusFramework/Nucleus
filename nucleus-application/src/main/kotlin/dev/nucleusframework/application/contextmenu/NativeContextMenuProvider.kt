@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package dev.nucleusframework.application.contextmenu

import androidx.compose.foundation.LocalContextMenuRepresentation
import androidx.compose.foundation.text.LocalTextContextMenu
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import dev.nucleusframework.core.runtime.Platform
import dev.nucleusframework.menu.macos.isNativePopupMenuAvailable as isMacOsNativePopupMenuAvailable

/**
 * `true` when [NativeContextMenuProvider] has installed the native
 * representation in this subtree. Spellcheck uses this to stop drawing its
 * own Compose popup (separators are native rows instead).
 */
public val LocalNativeContextMenu: ProvidableCompositionLocal<Boolean> =
    staticCompositionLocalOf { false }

/**
 * Window density captured by [NativeContextMenuProvider], above any
 * subtree [LocalDensity] override (e.g. the demo Gallery at 75%).
 * The Windows Compose flyout uses this so it stays at OS scale.
 */
internal val LocalContextMenuDensity: ProvidableCompositionLocal<Density?> =
    staticCompositionLocalOf { null }

/**
 * Whether this process can show the opt-in native-looking context menu:
 * macOS + `menu-macos`, or Windows (Compose Fluent flyout). Linux is
 * always `false`.
 */
public val isNativeContextMenuSupported: Boolean
    get() =
        when (Platform.Current) {
            Platform.MacOS -> isMacOsNativePopupMenuAvailable
            Platform.Windows -> true
            else -> false
        }

/**
 * Jewel-style context-menu provider: installs [NativeTextContextMenu] (so
 * Cut / Copy / Paste carry [ContextMenuIcon] stock tags) and
 * [NativeContextMenuRepresentation] (`NSMenu` on macOS, Compose Fluent
 * flyout on Windows).
 *
 * No-op when [enabled] is `false` or when [isNativeContextMenuSupported] is
 * `false` (Linux, missing macOS native lib). Compose / Jewel chrome stays.
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
    val windowDensity = LocalDensity.current
    CompositionLocalProvider(
        LocalNativeContextMenu provides true,
        LocalContextMenuDensity provides windowDensity,
        LocalContextMenuRepresentation provides NativeContextMenuRepresentation,
        LocalTextContextMenu provides NativeTextContextMenu,
        content = content,
    )
}
