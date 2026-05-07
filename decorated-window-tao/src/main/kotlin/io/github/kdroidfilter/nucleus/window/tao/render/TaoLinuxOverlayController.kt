package io.github.kdroidfilter.nucleus.window.tao.render

import androidx.compose.runtime.compositionLocalOf
import io.github.kdroidfilter.nucleus.window.tao.NativeTaoLinuxWidgetBridge

/**
 * Linux-only counterpart of macOS's `NativeViewOverlayController` —
 * but **stripped of the rendering responsibility**. On Linux the
 * Compose surface already paints on top of the embedded GTK widget,
 * so the `content` slot of `NativeView` can be rendered inline in
 * the main Compose scene. The OS-level concern is **input routing**:
 * by default, every click on the Compose region falls through to the
 * embedded native widget (the EGL subsurface is input-transparent),
 * which is exactly what we want for non-interactive overlay pixels.
 *
 * For *interactive* overlay regions (a `BasicTextField`, a `Button`,
 * etc., wrapped with [io.github.kdroidfilter.nucleus.window.tao.consumeOverlayPointerEvents])
 * we materialise an invisible `GtkEventBox` inside Tao's GtkOverlay,
 * positioned at the rect Compose reported. Clicks in the rect hit
 * the EventBox first (it's stacked above the embedded widget in
 * `gtk_overlay_add_overlay` order), bubble up unhandled to Tao's
 * `connect_button_press_event` handler at the GtkApplicationWindow
 * level, and reach the Compose scene through the normal pipeline.
 * Keystrokes follow the same path because the EventBox grabs GTK
 * focus on press.
 *
 * This is the GTK-native analogue of macOS's region-based
 * `NucleusTaoNativeOverlayView.hitTest:` — same UX semantics, but
 * implemented through GTK widgets instead of `wl_surface.set_input_region`
 * because nothing on our side listens for `wl_pointer.button` events
 * on the EGL subsurface.
 *
 * Threading: every method runs on the GTK main thread.
 */
internal interface TaoLinuxOverlayController {

    /**
     * Marks `(xPx, yPx, widthPx, heightPx)` as an interactive region
     * keyed by [key]. Re-registering the same key replaces the rect.
     * Internally creates / repositions a GtkEventBox.
     */
    fun registerRegion(
        key: Any,
        xPx: Int,
        yPx: Int,
        widthPx: Int,
        heightPx: Int,
    )

    /** Removes the rect previously registered under [key]. No-op if absent. */
    fun unregisterRegion(key: Any)
}

/**
 * Provided by `DecoratedWindow` on Linux; null elsewhere.
 */
internal val LocalTaoLinuxOverlayController =
    compositionLocalOf<TaoLinuxOverlayController?> { null }

/**
 * Concrete impl. Maintains one `GtkEventBox` handle per registered
 * key inside the GtkOverlay injected into Tao's content widget tree.
 * Mirrors the rect-management pattern of macOS's
 * `NativeViewOverlayController.regions` / `flushRegions`, but each
 * region is its own GTK widget rather than a flat rect list passed
 * to the compositor.
 */
internal class TaoLinuxOverlayControllerImpl(
    private val gtkWindowProvider: () -> Long,
    /** Compose physical / scale → GTK logical pixels. */
    private val scaleProvider: () -> Float,
    /**
     * Where to dispatch the synthetic pointer events the EventBox
     * sends through. The host's `onPointerMove` / `onPointerButton`
     * mutate `lastPointerX/Y` and forward to the active
     * `ComposeScene`, which is exactly what we want — it puts Linux
     * overlay input on the same path as Tao's native button-press
     * dispatch. Passed as a tiny adapter to avoid a hard dependency
     * on the concrete host class.
     */
    private val moveDispatcher: (xPx: Int, yPx: Int) -> Unit,
    private val buttonDispatcher: (button: Int, pressed: Boolean) -> Unit,
    /**
     * Called when the GTK EventBox loses focus (= user clicked
     * somewhere outside our overlay, e.g. on the embedded WebView).
     * Compose's `focusManager.releaseFocus()` is invoked here so a
     * focused `BasicTextField` visually deselects, mirroring macOS's
     * `resignFirstResponder` behaviour.
     */
    private val focusReleaseDispatcher: () -> Unit,
) : TaoLinuxOverlayController {

    /** key → GtkEventBox pointer (0 if creation failed). */
    private val boxes: MutableMap<Any, Long> = LinkedHashMap()

    /**
     * Translates the EventBox's logical pixel reports back into
     * Compose's physical pixel space (matching what Tao's
     * `LogicalPosition::to_physical(scale)` would have produced) and
     * dispatches into the host. Logical px → physical px = ×scale.
     */
    private inner class InputCallback : NativeTaoLinuxWidgetBridge.OverlayInputCallback {
        override fun onEvent(type: Int, xLogical: Int, yLogical: Int, button: Int, pressed: Int) {
            if (type == 3) {
                // FOCUS_OUT — coords are 0/0 placeholders.
                focusReleaseDispatcher()
                return
            }
            val s = scaleProvider().takeIf { it > 0f } ?: 1f
            val xPx = (xLogical * s).toInt()
            val yPx = (yLogical * s).toInt()
            moveDispatcher(xPx, yPx)
            when (type) {
                1 -> buttonDispatcher(button, true)   // press
                2 -> buttonDispatcher(button, false)  // release
            }
        }
    }

    override fun registerRegion(
        key: Any,
        xPx: Int,
        yPx: Int,
        widthPx: Int,
        heightPx: Int,
    ) {
        if (!NativeTaoLinuxWidgetBridge.isLoaded) return
        val gtkWindow = gtkWindowProvider()
        if (gtkWindow == 0L) return

        val s = scaleProvider().takeIf { it > 0f } ?: 1f
        val xL = (xPx / s).toInt()
        val yL = (yPx / s).toInt()
        val wL = (widthPx / s).toInt().coerceAtLeast(1)
        val hL = (heightPx / s).toInt().coerceAtLeast(1)

        var handle = boxes[key]
        if (handle == null) {
            handle = NativeTaoLinuxWidgetBridge.nativeAddInputBox(gtkWindow)
            if (handle == 0L) return
            boxes[key] = handle
            NativeTaoLinuxWidgetBridge.nativeSetInputBoxCallback(handle, InputCallback())
        }
        NativeTaoLinuxWidgetBridge.nativeMoveInputBox(handle, xL, yL, wL, hL)
    }

    override fun unregisterRegion(key: Any) {
        val handle = boxes.remove(key) ?: return
        if (handle != 0L && NativeTaoLinuxWidgetBridge.isLoaded) {
            NativeTaoLinuxWidgetBridge.nativeRemoveInputBox(handle)
        }
    }

    fun dispose() {
        if (boxes.isEmpty()) return
        if (NativeTaoLinuxWidgetBridge.isLoaded) {
            for (handle in boxes.values) {
                if (handle != 0L) NativeTaoLinuxWidgetBridge.nativeRemoveInputBox(handle)
            }
        }
        boxes.clear()
    }
}
