package dev.nucleusframework.window.utils.windows

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import dev.nucleusframework.window.LocalIsDarkTheme
import dev.nucleusframework.window.icons.windows.Close
import dev.nucleusframework.window.icons.windows.CloseDark
import dev.nucleusframework.window.icons.windows.CloseFullscreen
import dev.nucleusframework.window.icons.windows.CloseFullscreenDark
import dev.nucleusframework.window.icons.windows.CloseFullscreenInactive
import dev.nucleusframework.window.icons.windows.CloseFullscreenInactiveDark
import dev.nucleusframework.window.icons.windows.CloseHover
import dev.nucleusframework.window.icons.windows.CloseInactive
import dev.nucleusframework.window.icons.windows.CloseInactiveDark
import dev.nucleusframework.window.icons.windows.Maximize
import dev.nucleusframework.window.icons.windows.MaximizeDark
import dev.nucleusframework.window.icons.windows.MaximizeInactive
import dev.nucleusframework.window.icons.windows.MaximizeInactiveDark
import dev.nucleusframework.window.icons.windows.Minimize
import dev.nucleusframework.window.icons.windows.MinimizeDark
import dev.nucleusframework.window.icons.windows.MinimizeInactive
import dev.nucleusframework.window.icons.windows.MinimizeInactiveDark
import dev.nucleusframework.window.icons.windows.Restore
import dev.nucleusframework.window.icons.windows.RestoreDark
import dev.nucleusframework.window.icons.windows.RestoreInactive
import dev.nucleusframework.window.icons.windows.RestoreInactiveDark
import dev.nucleusframework.window.icons.windows.WindowsControlButtonIcons

public data class WindowsTitleBarIconSet(
    val close: Painter,
    val closeHover: Painter,
    val closeInactive: Painter,
    val minimize: Painter,
    val minimizeInactive: Painter,
    val maximize: Painter,
    val maximizeInactive: Painter,
    val restore: Painter,
    val restoreInactive: Painter,
    val exitFullscreen: Painter,
    val exitFullscreenInactive: Painter,
)

@Composable
public fun windowsTitleBarIcons(isDark: Boolean = LocalIsDarkTheme.current): WindowsTitleBarIconSet =
    if (isDark) {
        WindowsTitleBarIconSet(
            close = rememberVectorPainter(WindowsControlButtonIcons.CloseDark),
            closeHover = rememberVectorPainter(WindowsControlButtonIcons.CloseHover),
            closeInactive = rememberVectorPainter(WindowsControlButtonIcons.CloseInactiveDark),
            minimize = rememberVectorPainter(WindowsControlButtonIcons.MinimizeDark),
            minimizeInactive = rememberVectorPainter(WindowsControlButtonIcons.MinimizeInactiveDark),
            maximize = rememberVectorPainter(WindowsControlButtonIcons.MaximizeDark),
            maximizeInactive = rememberVectorPainter(WindowsControlButtonIcons.MaximizeInactiveDark),
            restore = rememberVectorPainter(WindowsControlButtonIcons.RestoreDark),
            restoreInactive = rememberVectorPainter(WindowsControlButtonIcons.RestoreInactiveDark),
            exitFullscreen = rememberVectorPainter(WindowsControlButtonIcons.CloseFullscreenDark),
            exitFullscreenInactive = rememberVectorPainter(WindowsControlButtonIcons.CloseFullscreenInactiveDark),
        )
    } else {
        WindowsTitleBarIconSet(
            close = rememberVectorPainter(WindowsControlButtonIcons.Close),
            closeHover = rememberVectorPainter(WindowsControlButtonIcons.CloseHover),
            closeInactive = rememberVectorPainter(WindowsControlButtonIcons.CloseInactive),
            minimize = rememberVectorPainter(WindowsControlButtonIcons.Minimize),
            minimizeInactive = rememberVectorPainter(WindowsControlButtonIcons.MinimizeInactive),
            maximize = rememberVectorPainter(WindowsControlButtonIcons.Maximize),
            maximizeInactive = rememberVectorPainter(WindowsControlButtonIcons.MaximizeInactive),
            restore = rememberVectorPainter(WindowsControlButtonIcons.Restore),
            restoreInactive = rememberVectorPainter(WindowsControlButtonIcons.RestoreInactive),
            exitFullscreen = rememberVectorPainter(WindowsControlButtonIcons.CloseFullscreen),
            exitFullscreenInactive = rememberVectorPainter(WindowsControlButtonIcons.CloseFullscreenInactive),
        )
    }
