package dev.nucleusframework.window

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import dev.nucleusframework.window.tao.LocalWindowClearColorLayers

/**
 * Sets the window's own background — the colour that shows wherever Compose
 * paints nothing.
 *
 * That is more places than it sounds: the margins around a floating panel, the
 * band a chrome animation briefly leaves open, and above all the frames the
 * compositor draws during a live resize, which is what makes an unset
 * background flash white on a dark app.
 *
 * A themed app should call this with the same colour its theme paints,
 * anywhere inside the window content:
 *
 * ```
 * DecoratedWindow(...) {
 *     MaterialTheme(colorScheme = scheme) {
 *         WindowBackground(scheme.background)
 *         WindowScaffold(...) { ... }
 *     }
 * }
 * ```
 *
 * Shares the content clear stack with `TitleBar`: co-composed, the last
 * SideEffect wins; when either leaves composition only its own contribution
 * is dropped, so this colour is restored if `TitleBar` is removed.
 *
 * The alternative is providing `LocalDecoratedWindowStyle` *around* the
 * window, which forces the theme state to be hoisted above it — awkward
 * precisely when the theme lives inside, as it must on the Tao backend where
 * every window owns its `ComposeScene`.
 */
@Suppress("FunctionNaming", "UnusedReceiverParameter")
@Composable
public fun DecoratedWindowScope.WindowBackground(color: Color) {
    val layers = LocalWindowClearColorLayers.current
    val writerKey = remember { Any() }
    val argb = color.toArgb()
    SideEffect {
        // Keyed write to the content stack; the layer resolver pushes the
        // result into the host state synchronously, so the first composition
        // themes the window before its first frame. Every consumer — the Skia
        // clear, the NSWindow colour on macOS, the HWND brush + DWM
        // caption/dark-mode on Windows — derives from that one resolved state.
        layers?.setContent(writerKey, argb)
    }
    DisposableEffect(writerKey) {
        onDispose {
            // Drop only this writer: a co-composed TitleBar (or another
            // WindowBackground) must keep its contribution.
            layers?.clearContent(writerKey)
        }
    }
}
