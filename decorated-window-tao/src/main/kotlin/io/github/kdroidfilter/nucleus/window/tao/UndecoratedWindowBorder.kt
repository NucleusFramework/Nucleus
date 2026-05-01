package io.github.kdroidfilter.nucleus.window.tao

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.kdroidfilter.nucleus.core.runtime.LinuxDesktopEnvironment
import io.github.kdroidfilter.nucleus.window.DecoratedWindowState
import io.github.kdroidfilter.nucleus.window.internal.insideBorder
import io.github.kdroidfilter.nucleus.window.styling.LocalDecoratedWindowStyle

/**
 * Returns the [Modifier.insideBorder] used by `DecoratedWindow` on
 * Linux + Windows when running undecorated (no native chrome) — same logic
 * as `decorated-window-jni`'s `DecoratedWindowBody`. macOS keeps its native
 * decorations and does not need a Compose-drawn border.
 *
 * Returns [Modifier] (no-op) when the window is maximized/fullscreen — the
 * border would otherwise overflow into the system reserved area.
 */
@Composable
internal fun rememberUndecoratedWindowBorder(
    state: DecoratedWindowState,
    linuxDe: LinuxDesktopEnvironment,
    gnomeCornerArc: Float,
    kdeCornerArc: Float,
): Modifier {
    if (state.isMaximized || state.isFullscreen) return Modifier
    val style = LocalDecoratedWindowStyle.current
    val borderShape =
        when (linuxDe) {
            LinuxDesktopEnvironment.Gnome ->
                RoundedCornerShape((gnomeCornerArc / 2).dp)
            LinuxDesktopEnvironment.KDE ->
                RoundedCornerShape(
                    topStart = (kdeCornerArc / 2).dp,
                    topEnd = (kdeCornerArc / 2).dp,
                    bottomStart = 0.dp,
                    bottomEnd = 0.dp,
                )
            else -> RoundedCornerShape(0.dp)
        }
    val color by style.colors.borderFor(state)
    return Modifier.insideBorder(
        width = style.metrics.borderWidth,
        color = color,
        shape = borderShape,
    )
}
