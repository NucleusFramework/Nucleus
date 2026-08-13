@file:Suppress("MagicNumber")

package dev.nucleusframework.window.tao.deco

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.nucleusframework.core.runtime.LinuxDesktopEnvironment
import dev.nucleusframework.core.runtime.Platform
import dev.nucleusframework.window.DecoratedWindowState
import dev.nucleusframework.window.internal.insideBorder
import dev.nucleusframework.window.styling.LocalDecoratedWindowStyle

// Soft elevation stroke layered under a dialog's regular border to lift it off
// the (dimmed) parent. The window-border layer runs above where the app theme
// is bridged into the Tao scene, so we can't read light/dark here. Instead we
// stack a faint-black stroke on top of the default faint-white one: the black
// is only visible on light surfaces, the white only on dark, so the elevation
// edge adapts to either theme without needing the resolved colors.
private val DialogElevationStroke = Color(0x1F000000)

/**
 * Returns the [Modifier.insideBorder] used by Tao `DecoratedWindow` on
 * Linux + Windows for the **default custom-chrome** look (no native frame —
 * same role as `decorated-window-jni`'s `DecoratedWindowBody` border). macOS
 * keeps native decorations and does not need this.
 *
 * Callers that pass `undecorated = true` (fully borderless overlays / ghosts)
 * must **not** apply this modifier — that matches vanilla Compose Desktop
 * `Window(undecorated = true)` (no framework-drawn contour).
 *
 * When [isDialog] is set, a faint shadow-toned stroke is layered on top so the
 * dialog reads as an elevated surface above its dimmed parent (Linux only —
 * elsewhere the compositor draws a native drop shadow).
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
    isDialog: Boolean = false,
): Modifier {
    if (state.isMaximized || state.isFullscreen) return Modifier
    val style = LocalDecoratedWindowStyle.current
    val borderShape =
        when {
            // Tiled/snapped windows sit flush against the screen edge with
            // squared-off corners (the rounded-corner carve is also disabled
            // when tiled). Keep a rectangular border so the visible outline
            // matches the square corners instead of a rounded line over them.
            state.isTiled -> RoundedCornerShape(0.dp)
            linuxDe == LinuxDesktopEnvironment.Gnome ->
                RoundedCornerShape((gnomeCornerArc / 2).dp)
            linuxDe == LinuxDesktopEnvironment.KDE ->
                RoundedCornerShape(
                    topStart = (kdeCornerArc / 2).dp,
                    topEnd = (kdeCornerArc / 2).dp,
                    bottomStart = 0.dp,
                    bottomEnd = 0.dp,
                )
            // Win11 DWMWCP_ROUND is 8 logical px. A square stroke on a
            // rounded HWND is clipped at the corners and reads as a box.
            Platform.Current == Platform.Windows -> RoundedCornerShape(8.dp)
            else -> RoundedCornerShape(0.dp)
        }
    val color by style.colors.borderFor(state)
    var modifier =
        Modifier.insideBorder(
            width = style.metrics.borderWidth,
            color = color,
            shape = borderShape,
        )
    // Linux dialogs are undecorated, so the compositor draws no drop shadow —
    // lift them with an extra elevation stroke. Gated on a known Linux DE
    // (Windows passes Unknown) to keep native-shadowed platforms untouched.
    if (isDialog && linuxDe != LinuxDesktopEnvironment.Unknown) {
        modifier =
            modifier.insideBorder(
                width = style.metrics.borderWidth,
                color = DialogElevationStroke,
                shape = borderShape,
            )
    }
    return modifier
}
