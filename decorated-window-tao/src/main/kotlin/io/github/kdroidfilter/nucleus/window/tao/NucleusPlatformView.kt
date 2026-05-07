package io.github.kdroidfilter.nucleus.window.tao

import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import java.nio.ByteBuffer

/**
 * Platform-agnostic descriptor for a native view embedded by the
 * [NativeView] composable. Concrete implementors are platform-specific:
 *
 *  - [NsView] on macOS — direct AppKit subview embedding via Tao's
 *    NSView host. Lowest latency, full input/IME, hardware-accelerated.
 *    Implementor exposes a raw `NSView*` handle.
 *  - [HWnd] on Windows — child HWND embedding via `SetParent`. Not yet
 *    implemented (composable falls back to no-op); the variant exists
 *    so the API can ship cross-platform without later breaking changes.
 *  - [Texture] on Linux — texture-based composition mirroring Sony's
 *    `flutter-embedded-linux` and Flutter Windows roadmap. The user
 *    widget renders offscreen and publishes frames either as a CPU
 *    [Texture.Frame.PixelBuffer] or a zero-copy
 *    [Texture.Frame.EglImage]; Compose draws the resulting GL texture
 *    inside the scene graph. No subsurface, no z-order tricks; works
 *    identically under X11 and Wayland.
 *
 * The default empty implementations let host code call lifecycle
 * methods unconditionally without forcing every variant to override
 * methods it doesn't care about (e.g. an `NsView` doesn't need
 * `clearFocus` since AppKit owns focus management).
 */
sealed interface NucleusPlatformView {

    /** Called when the embedded view's logical bounds change. */
    fun resize(widthPx: Int, heightPx: Int) {}

    /**
     * Asks the view to release keyboard focus. Used when the host
     * window or a sibling Compose layer takes focus and the embedded
     * view should visually deselect.
     */
    fun clearFocus() {}

    /**
     * Final teardown. After this returns, the platform handle is no
     * longer accessed by Nucleus. Implementations should release any
     * native resources they own.
     */
    fun dispose() {}

    /**
     * macOS variant — embedded as a sibling `NSView` of the Tao host's
     * content view, with an optional Compose overlay rendered into a
     * `CAMetalLayer` of its own. See `NativeViewOverlayController`.
     */
    interface NsView : NucleusPlatformView {
        /** Pointer to the user-supplied `NSView*` (top-bit clear). */
        val nsViewHandle: Long
    }

    /**
     * Windows variant — child HWND attached via `SetParent`, with an
     * overlay HWND using `WS_EX_LAYERED | WS_EX_TRANSPARENT` for the
     * Compose `content` slot. **Not implemented yet** — the variant
     * exists so the API surface is forward-compatible.
     */
    interface HWnd : NucleusPlatformView {
        /** Pointer to the user-supplied `HWND` (cast to Long). */
        val hwndHandle: Long
    }

    /**
     * Linux variant — texture-based composition. The widget renders
     * offscreen (GtkOffscreenWindow, WebKit2GTK with WPE backend, GL
     * FBO, …) and publishes its frame via [acquireFrame]; Compose
     * picks it up on the next draw and uploads / imports it into the
     * Skia/GL context owning the scene.
     *
     * Mirrors `FlutterDesktopPlatformView` from
     * `sony/flutter-embedded-linux` (`flutter_platform_views.h`).
     */
    interface Texture : NucleusPlatformView {

        /**
         * Returns the latest frame produced by the widget, or null if
         * no frame is available yet. The composable calls this once
         * per draw pass on the EGL/render thread (so GL uploads can
         * happen inline).
         */
        fun acquireFrame(): Frame?

        /**
         * Notifies the view that the frame returned by [acquireFrame]
         * has been consumed (uploaded to the GL texture or imported
         * via `glEGLImageTargetTexture2DOES`). Default no-op for views
         * that own a stable buffer pool.
         */
        fun releaseFrame(frame: Frame) {}

        /**
         * The widget calls [listener] whenever a new frame is ready,
         * causing the composable to invalidate and re-draw. Pass null
         * to clear the listener at dispose time.
         */
        fun setOnFrameAvailable(listener: (() -> Unit)?)

        /**
         * Synthetic pointer event in widget-local physical pixels
         * (top-left origin). [button] is null for hover / move events.
         */
        fun onPointer(
            type: PointerEventType,
            xPx: Float,
            yPx: Float,
            button: PointerButton?,
        ) {}

        /**
         * Synthetic scroll event in widget-local physical pixels.
         * Deltas use the same units as Compose's `PointerEvent`
         * scrollDelta.
         */
        fun onScroll(xPx: Float, yPx: Float, dxPx: Float, dyPx: Float) {}

        /**
         * Forwarded keyboard event. Returning true marks the event as
         * consumed and stops Compose's propagation.
         */
        fun onKey(event: KeyEvent): Boolean = false

        /**
         * Frame envelope produced by the embedded widget. Two flavours:
         * a CPU pixel buffer for software widgets and an EGLImage for
         * zero-copy GPU widgets (WebKit2GTK with WPE, GStreamer with
         * `glupload`, …).
         */
        sealed interface Frame {

            val widthPx: Int
            val heightPx: Int

            /**
             * RGBA8 pixel buffer in widget-local memory. The composable
             * uploads via `glTexSubImage2D`; [rgba] must remain valid
             * until [releaseFrame] returns.
             */
            data class PixelBuffer(
                val rgba: ByteBuffer,
                override val widthPx: Int,
                override val heightPx: Int,
            ) : Frame

            /**
             * `EGLImageKHR` handle (cast to Long). The composable
             * imports it as a `GL_TEXTURE_2D` via
             * `glEGLImageTargetTexture2DOES` inside the host's EGL
             * context. The image must remain valid until [releaseFrame]
             * returns.
             */
            data class EglImage(
                val image: Long,
                override val widthPx: Int,
                override val heightPx: Int,
            ) : Frame
        }
    }
}
