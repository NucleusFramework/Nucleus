package io.github.kdroidfilter.nucleus.window.utils.linux

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import io.github.kdroidfilter.nucleus.core.runtime.LinuxDesktopEnvironment
import io.github.kdroidfilter.nucleus.window.LocalIsDarkTheme
import io.github.kdroidfilter.nucleus.window.icons.linux.gnome.*
import io.github.kdroidfilter.nucleus.window.icons.linux.kde.*

data class LinuxTitleBarIconSet(
    val close: Painter,
    val closeHover: Painter,
    val closePressed: Painter,
    val closeHoverFocused: Painter,
    val closePressedFocused: Painter,
    val closeInactive: Painter,
    val minimize: Painter,
    val minimizeHover: Painter,
    val minimizePressed: Painter,
    val minimizeInactive: Painter,
    val maximize: Painter,
    val maximizeHover: Painter,
    val maximizePressed: Painter,
    val maximizeInactive: Painter,
    val restore: Painter,
    val restoreHover: Painter,
    val restorePressed: Painter,
    val restoreInactive: Painter,
)

@Composable
fun linuxTitleBarIcons(
    de: LinuxDesktopEnvironment = LinuxDesktopEnvironment.Current,
    isDark: Boolean = LocalIsDarkTheme.current,
): LinuxTitleBarIconSet {
    val isKde = de == LinuxDesktopEnvironment.KDE
    return if (isKde) kdeIcons(isDark) else gnomeIcons(isDark)
}

@Composable
private fun icon(
    light: ImageVector,
    dark: ImageVector,
    isDark: Boolean,
): Painter = rememberVectorPainter(if (isDark) dark else light)

@Composable
private fun gnomeIcons(isDark: Boolean): LinuxTitleBarIconSet {
    val g = GnomeControlButtonsIcons
    val closeHover = icon(g.CloseHover, g.CloseHoverDark, isDark)
    val closePressed = icon(g.ClosePressed, g.ClosePressedDark, isDark)
    val minimize = icon(g.Minimize, g.MinimizeDark, isDark)
    val maximize = icon(g.Maximize, g.MaximizeDark, isDark)
    val restore = icon(g.Restore, g.RestoreDark, isDark)

    return LinuxTitleBarIconSet(
        close = icon(g.Close, g.CloseDark, isDark),
        closeHover = closeHover,
        closePressed = closePressed,
        closeHoverFocused = closeHover,
        closePressedFocused = closePressed,
        closeInactive = icon(g.CloseInactive, g.CloseInactiveDark, isDark),
        minimize = minimize,
        minimizeHover = icon(g.MinimizeHover, g.MinimizeHoverDark, isDark),
        minimizePressed = icon(g.MinimizePressed, g.MinimizePressedDark, isDark),
        minimizeInactive = icon(g.MinimizeInactive, g.MinimizeInactiveDark, isDark),
        maximize = maximize,
        maximizeHover = icon(g.MaximizeHover, g.MaximizeHoverDark, isDark),
        maximizePressed = icon(g.MaximizePressed, g.MaximizePressedDark, isDark),
        maximizeInactive = icon(g.MaximizeInactive, g.MaximizeInactiveDark, isDark),
        restore = restore,
        restoreHover = icon(g.RestoreHover, g.RestoreHoverDark, isDark),
        restorePressed = icon(g.RestorePressed, g.RestorePressedDark, isDark),
        restoreInactive = icon(g.RestoreInactive, g.RestoreInactiveDark, isDark),
    )
}

@Composable
private fun kdeIcons(isDark: Boolean): LinuxTitleBarIconSet {
    val k = KdeControlButtonsIcons
    // KDE close hover/pressed icons are the same in light and dark
    val closeHover = rememberVectorPainter(k.CloseHover)
    val closePressed = rememberVectorPainter(k.ClosePressed)
    val minimize = icon(k.Minimize, k.MinimizeDark, isDark)
    val maximize = icon(k.Maximize, k.MaximizeDark, isDark)
    val restore = icon(k.Restore, k.RestoreDark, isDark)

    return LinuxTitleBarIconSet(
        close = icon(k.Close, k.CloseDark, isDark),
        closeHover = closeHover,
        closePressed = closePressed,
        closeHoverFocused = rememberVectorPainter(k.CloseHoverFocused),
        closePressedFocused = rememberVectorPainter(k.ClosePressedFocused),
        closeInactive = icon(k.CloseInactive, k.CloseInactiveDark, isDark),
        minimize = minimize,
        minimizeHover = icon(k.MinimizeHover, k.MinimizeHoverDark, isDark),
        minimizePressed = icon(k.MinimizePressed, k.MinimizePressedDark, isDark),
        // KDE dark: inactive icons are identical to normal icons
        minimizeInactive = if (isDark) minimize else rememberVectorPainter(k.MinimizeInactive),
        maximize = maximize,
        maximizeHover = icon(k.MaximizeHover, k.MaximizeHoverDark, isDark),
        maximizePressed = icon(k.MaximizePressed, k.MaximizePressedDark, isDark),
        maximizeInactive = if (isDark) maximize else rememberVectorPainter(k.MaximizeInactive),
        restore = restore,
        restoreHover = icon(k.RestoreHover, k.RestoreHoverDark, isDark),
        restorePressed = icon(k.RestorePressed, k.RestorePressedDark, isDark),
        restoreInactive = if (isDark) restore else rememberVectorPainter(k.RestoreInactive),
    )
}
