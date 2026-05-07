package io.github.kdroidfilter.nucleus.window.tao

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import io.github.kdroidfilter.nucleus.window.tao.render.LocalTaoLinuxOverlayController
import kotlin.math.roundToInt

/**
 * Marks the modified Compose region as a pointer-event **consumer**
 * inside a surrounding [NativeView]'s `content` overlay slot. Native
 * pointer events that land inside the modified bounds are intercepted
 * by the Compose overlay and routed to its scene (so a
 * `BasicTextField`, `Button`, etc. wrapped with this modifier behave
 * like normal Compose UI). Events outside any consumer region pass
 * through to the underlying native widget (typically a `WKWebView` /
 * `WebKitWebView`).
 *
 *  - **macOS**: the consumer rect is registered with the overlay
 *    controller, which feeds the sibling overlay NSView's
 *    region-based `hitTest:`. AppKit's native `mouseDown:` handler
 *    automatically calls `[window makeFirstResponder:overlay]` so
 *    keystrokes route to the overlay's `ComposeScene` without any
 *    extra Kotlin-side hop.
 *  - **Linux**: the consumer rect is registered with the Linux
 *    overlay controller, which updates the EGL subsurface's input
 *    region (`wl_surface.set_input_region` / `XShape ShapeInput`).
 *    Releasing GTK's focused widget on press happens at a different
 *    layer (`GtkWidgetEmbedding`'s outer Box) so this modifier stays
 *    a pure observer of layout — adding a `pointerInput` here was
 *    found to silently swallow events from descendants in some
 *    Compose configurations.
 *  - **Windows / outside any `NativeView`**: no-op so call sites
 *    stay portable.
 */
fun Modifier.consumeOverlayPointerEvents(): Modifier = composed {
    val mac = LocalNativeViewOverlayController.current
    val linux = LocalTaoLinuxOverlayController.current
    val windows = LocalNativeViewOverlayControllerWindows.current
    if (mac == null && linux == null && windows == null) return@composed this

    val key = remember { Any() }
    DisposableEffect(mac, linux, windows, key) {
        onDispose {
            mac?.unregisterRegion(key)
            linux?.unregisterRegion(key)
            windows?.unregisterRegion(key)
        }
    }

    onGloballyPositioned { coords ->
        val pos = coords.positionInRoot()
        val xPx = pos.x.roundToInt()
        val yPx = pos.y.roundToInt()
        val wPx = coords.size.width
        val hPx = coords.size.height
        mac?.registerRegion(key, xPx, yPx, wPx, hPx)
        linux?.registerRegion(key, xPx, yPx, wPx, hPx)
        windows?.registerRegion(key, xPx, yPx, wPx, hPx)
    }
}
