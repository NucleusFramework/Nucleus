package dev.nucleusframework.nucleus.window.utils.windows

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import dev.nucleusframework.nucleus.window.LocalIsDarkTheme
import dev.nucleusframework.nucleus.window.icons.windows.Close
import dev.nucleusframework.nucleus.window.icons.windows.CloseDark
import dev.nucleusframework.nucleus.window.icons.windows.CloseFullscreen
import dev.nucleusframework.nucleus.window.icons.windows.CloseFullscreenDark
import dev.nucleusframework.nucleus.window.icons.windows.CloseFullscreenInactive
import dev.nucleusframework.nucleus.window.icons.windows.CloseFullscreenInactiveDark
import dev.nucleusframework.nucleus.window.icons.windows.CloseHover
import dev.nucleusframework.nucleus.window.icons.windows.CloseInactive
import dev.nucleusframework.nucleus.window.icons.windows.CloseInactiveDark
import dev.nucleusframework.nucleus.window.icons.windows.Maximize
import dev.nucleusframework.nucleus.window.icons.windows.MaximizeDark
import dev.nucleusframework.nucleus.window.icons.windows.MaximizeInactive
import dev.nucleusframework.nucleus.window.icons.windows.MaximizeInactiveDark
import dev.nucleusframework.nucleus.window.icons.windows.Minimize
import dev.nucleusframework.nucleus.window.icons.windows.MinimizeDark
import dev.nucleusframework.nucleus.window.icons.windows.MinimizeInactive
import dev.nucleusframework.nucleus.window.icons.windows.MinimizeInactiveDark
import dev.nucleusframework.nucleus.window.icons.windows.Restore
import dev.nucleusframework.nucleus.window.icons.windows.RestoreDark
import dev.nucleusframework.nucleus.window.icons.windows.RestoreInactive
import dev.nucleusframework.nucleus.window.icons.windows.RestoreInactiveDark
import dev.nucleusframework.nucleus.window.icons.windows.WindowsControlButtonIcons

data class WindowsTitleBarIconSet(
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
fun windowsTitleBarIcons(isDark: Boolean = LocalIsDarkTheme.current): WindowsTitleBarIconSet =
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
