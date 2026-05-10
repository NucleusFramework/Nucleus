package io.github.kdroidfilter.nucleus.window.utils.windows

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import io.github.kdroidfilter.nucleus.window.LocalIsDarkTheme
import io.github.kdroidfilter.nucleus.window.icons.windows.Close
import io.github.kdroidfilter.nucleus.window.icons.windows.CloseDark
import io.github.kdroidfilter.nucleus.window.icons.windows.CloseFullscreen
import io.github.kdroidfilter.nucleus.window.icons.windows.CloseFullscreenDark
import io.github.kdroidfilter.nucleus.window.icons.windows.CloseFullscreenInactive
import io.github.kdroidfilter.nucleus.window.icons.windows.CloseFullscreenInactiveDark
import io.github.kdroidfilter.nucleus.window.icons.windows.CloseHover
import io.github.kdroidfilter.nucleus.window.icons.windows.CloseInactive
import io.github.kdroidfilter.nucleus.window.icons.windows.CloseInactiveDark
import io.github.kdroidfilter.nucleus.window.icons.windows.Maximize
import io.github.kdroidfilter.nucleus.window.icons.windows.MaximizeDark
import io.github.kdroidfilter.nucleus.window.icons.windows.MaximizeInactive
import io.github.kdroidfilter.nucleus.window.icons.windows.MaximizeInactiveDark
import io.github.kdroidfilter.nucleus.window.icons.windows.Minimize
import io.github.kdroidfilter.nucleus.window.icons.windows.MinimizeDark
import io.github.kdroidfilter.nucleus.window.icons.windows.MinimizeInactive
import io.github.kdroidfilter.nucleus.window.icons.windows.MinimizeInactiveDark
import io.github.kdroidfilter.nucleus.window.icons.windows.Restore
import io.github.kdroidfilter.nucleus.window.icons.windows.RestoreDark
import io.github.kdroidfilter.nucleus.window.icons.windows.RestoreInactive
import io.github.kdroidfilter.nucleus.window.icons.windows.RestoreInactiveDark
import io.github.kdroidfilter.nucleus.window.icons.windows.WindowsControlButtonIcons

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
