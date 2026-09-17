package dev.nucleusframework.window

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Safe-area contract between the decorated window chrome and design-system
 * title bars.
 *
 * Provided by [WindowScaffold] so any custom chrome composable (a design
 * system's toolbar/headerbar) can lay itself out around the platform-reserved
 * zones without knowing platform specifics:
 * - [controlsInsets]: horizontal space reserved for the system window
 *   controls — the native macOS traffic-lights (side resolved against RTL),
 *   or the KDE Breeze edge padding on Linux. Zero when nothing is reserved.
 * - [titleBarHeight]: current measured height of the title bar slot, `0.dp`
 *   when no title bar is present (or it is hidden, e.g. in fullscreen).
 */
@Immutable
public data class WindowChromeInsets(
    public val controlsInsets: PaddingValues,
    public val titleBarHeight: Dp,
)

/**
 * The [WindowChromeInsets] of the closest [WindowScaffold] ancestor. Defaults
 * to zero insets outside of a scaffold.
 */
public val LocalWindowChromeInsets: ProvidableCompositionLocal<WindowChromeInsets> =
    compositionLocalOf { WindowChromeInsets(PaddingValues(0.dp), 0.dp) }
