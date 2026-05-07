package io.github.kdroidfilter.nucleus.window.tao.render

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.unit.IntSize
import io.github.kdroidfilter.nucleus.window.tao.NativeTaoLinuxWidgetBridge
import io.github.kdroidfilter.nucleus.window.tao.TaoMouseButton

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
     * Host content size in physical pixels. Used to size the
     * full-window capture box that catches clicks on Compose popups
     * extending beyond user-registered overlay rects.
     */
    private val hostSizeProvider: () -> IntSize,
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
    private inner class InputCallback(private val ownerKey: Any) : NativeTaoLinuxWidgetBridge.OverlayInputCallback {
        override fun onEvent(type: Int, xLogical: Int, yLogical: Int, button: Int, pressed: Int) {
            if (type == 3) {
                // FOCUS_OUT — coords are 0/0 placeholders.
                // Skip the synthetic outside-click dispatch when the
                // capture box is active: the focus loss was caused by
                // our own capture EventBox grabbing focus to keep the
                // just-opened Compose popup interactive. Firing the
                // outside-click here would dismiss the popup
                // immediately and send the user's next click into a
                // popup that no longer exists.
                if (!popupCaptureActive) {
                    focusReleaseDispatcher()
                }
                return
            }
            val s = scaleProvider().takeIf { it > 0f } ?: 1f
            val xPx = (xLogical * s).toInt()
            val yPx = (yLogical * s).toInt()
            moveDispatcher(xPx, yPx)
            when (type) {
                1 -> {
                    // Press. Dispatch first, then arm the capture box
                    // expansion if a Compose context menu likely just
                    // opened (right-click via a user-registered rect).
                    buttonDispatcher(button, true)
                    handlePressForPopupCapture(button, ownerKey)
                }
                2 -> {
                    // Release. Dispatch first so Compose sees the
                    // full press/release pair, then tear the capture
                    // box down (if this release dismisses the popup).
                    buttonDispatcher(button, false)
                    handleReleaseForPopupCapture(button, ownerKey)
                }
            }
        }
    }

    /**
     * Auto-managed reference count for the full-window capture
     * EventBox. A right-click via any user-registered overlay rect
     * activates capture (so the Compose context menu Compose just
     * opened gets full-area clickability — including items extending
     * beyond the originating rect). The next left-click via the
     * capture box dismisses it (Compose closes the menu, then we
     * shrink back to per-rect overlays so the embedded widget regains
     * interactivity).
     *
     * TODO(linux-popups): drop this heuristic once `xdg_popup` /
     *   `PlatformLayersComposeScene` integration ships — popups will
     *   render in their own surface and clicks will land natively
     *   without inflating the host's input region. See the commit
     *   history of `feat/tao-linux-native-view` for the in-progress
     *   implementation that proved out shared-EGL contexts and
     *   `DirectContext.resetGLAll`; the missing piece is Wayland
     *   `xdg_popup` positioning + grab plumbing.
     */
    private val popupCaptureKey: Any = object {}
    private var popupCaptureActive = false

    /**
     * Set on press, cleared on the matching release. Suppresses a
     * teardown that would otherwise fire mid-click (e.g. when the
     * right-click that *opens* the popup arrives via the capture box
     * because a previous popup left it up): we'd remove the box
     * before the release lands and Compose would see an unmatched
     * press, which never closes a menu.
     */
    private var capturePressPending = false

    private fun handlePressForPopupCapture(button: Int, sourceKey: Any) {
        if (sourceKey === popupCaptureKey) {
            // Press came through the capture box — defer teardown to
            // the matching release so Compose sees a complete
            // press/release pair.
            capturePressPending = true
            return
        }
        if (button == TaoMouseButton.RIGHT && !popupCaptureActive) {
            expandPopupCapture()
        }
    }

    private fun handleReleaseForPopupCapture(button: Int, sourceKey: Any) {
        if (sourceKey === popupCaptureKey && capturePressPending) {
            capturePressPending = false
            // Capture click cycle complete — popup has dismissed
            // itself (left-click dismissal) or accepted a menu item
            // (which also dismisses). Either way, the embedded
            // widget should regain interactivity now.
            shrinkPopupCapture()
        }
    }

    private fun expandPopupCapture() {
        val size = hostSizeProvider()
        if (size.width <= 0 || size.height <= 0) return
        // Reuses the existing capture EventBox if it was created in
        // a previous popup cycle (kept alive by [shrinkPopupCapture]
        // to avoid GLib-GObject warnings from rapid create+destroy
        // churn on Wayland NVIDIA).
        registerRegion(popupCaptureKey, 0, 0, size.width, size.height)
        popupCaptureActive = true
    }

    private fun shrinkPopupCapture() {
        if (!popupCaptureActive) return
        // Don't destroy the capture EventBox — repeated create+destroy
        // cycles trigger `g_signal_handler_disconnect` warnings about
        // stale handler IDs and `g_object_get_data` assertions on
        // partially-torn-down GObjects (visible as
        // `GLib-GObject-CRITICAL` log spam plus pointer-input lag
        // after 2-3 popup cycles). Move the box offscreen to a 1×1
        // allocation instead — visible_window=FALSE EventBoxes hit-
        // test against their allocation, so a 1×1 box at (-1,-1)
        // never catches any clicks. The box is fully torn down via
        // [dispose] when the window closes.
        registerRegion(popupCaptureKey, -1, -1, 1, 1)
        popupCaptureActive = false
        // Note: we deliberately do NOT call `focusReleaseDispatcher`
        // here. Doing so (synchronously OR deferred to the next
        // frame) interferes with the menu item's onClick action —
        // BasicTextField's Cut / Copy / Paste implementations
        // depend on the TextField focus + popup state staying
        // intact while their action runs.
        //
        // TODO(linux-popups): a follow-up should restore the
        //   "click outside Compose UI releases TextField focus"
        //   behaviour after a context-menu cycle. The capture box
        //   leaves the GTK focus chain in a state where no overlay
        //   rect owns focus, so `focus-out-event` never fires for
        //   subsequent embedded-widget clicks. Proper fix likely
        //   requires the per-popup `xdg_popup` architecture
        //   (see other linux-popups TODOs); for now the user can
        //   click another Compose widget to change focus.
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
            NativeTaoLinuxWidgetBridge.nativeSetInputBoxCallback(handle, InputCallback(key))
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
