package io.github.kdroidfilter.nucleus.window.tao

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import kotlin.math.roundToInt

/**
 * Marks the modified Compose region as a pointer-event **consumer**
 * inside a surrounding [NativeView]'s `content` overlay slot. AppKit
 * pointer events that land inside the modified bounds are intercepted
 * by the overlay panel and routed to its `ComposeScene` (so a
 * `BasicTextField`, `Button`, etc. wrapped with this modifier behave
 * like normal Compose UI). Events outside any consumer region pass
 * through to the underlying native subview (typically a `WKWebView`).
 *
 * Without this modifier, the overlay slot is effectively paint-only —
 * Compose pixels above the native view, but every click reaches the
 * view underneath. Wrap interactive widgets with
 * `consumeOverlayPointerEvents()` so they actually receive their
 * input.
 *
 * Outside a [NativeView]'s content slot (or on Windows / Linux), this
 * modifier is a no-op so call sites stay portable.
 */
fun Modifier.consumeOverlayPointerEvents(): Modifier = composed {
    val controller = LocalNativeViewOverlayController.current ?: return@composed this
    val key = remember { Any() }
    DisposableEffect(controller, key) {
        onDispose { controller.unregisterRegion(key) }
    }
    onGloballyPositioned { coords ->
        val pos = coords.positionInRoot()
        controller.registerRegion(
            key = key,
            xPx = pos.x.roundToInt(),
            yPx = pos.y.roundToInt(),
            widthPx = coords.size.width,
            heightPx = coords.size.height,
        )
    }
}
