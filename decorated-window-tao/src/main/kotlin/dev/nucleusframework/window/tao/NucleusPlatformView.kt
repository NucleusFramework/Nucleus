package dev.nucleusframework.window.tao

/**
 * Platform-agnostic descriptor for a native view embedded by the
 * [NativeView] composable. Concrete implementors are platform-specific:
 *
 *  - [NsView] on macOS — direct AppKit subview embedding via Tao's
 *    NSView host. Lowest latency, full input/IME, hardware-accelerated.
 *    Implementor exposes a raw `NSView*` handle.
 *  - [GtkWidget] on Linux — direct GTK widget embedding via
 *    `gtk_container_add` into Tao's GTK content widget. Implementor
 *    exposes a raw `GtkWidget*` handle (typically a `WebKitWebView`,
 *    `GtkGLArea`, etc.). The EGL surface paints above the widget;
 *    a GtkEventBox covering the NativeView rect lets Compose see hits
 *    first, then unconsumed events are synthesised back onto the widget.
 *  - [HWnd] on Windows — child HWND reparented under the Tao main HWND
 *    via `SetParent`, sized via `SetWindowPos`, clipped with
 *    `SetWindowRgn(CreateRoundRectRgn)` for rounded corners. A
 *    DirectComposition overlay composites the host scene over the
 *    embed so siblings and the `content` slot draw on top.
 *
 * The default empty implementations let host code call lifecycle
 * methods unconditionally without forcing every variant to override
 * methods it doesn't care about (e.g. an `NsView` doesn't need
 * `clearFocus` since AppKit owns focus management).
 */
public sealed interface NucleusPlatformView {
    /** Called when the embedded view's logical bounds change. */
    public fun resize(
        widthPx: Int,
        heightPx: Int,
    ) {}

    /**
     * Called with the embedded view's full bounds (position + size) in
     * physical pixels relative to the host window's client area. Default
     * is a no-op so most implementors can rely on the host's standard
     * `SetParent` + `SetWindowPos` (macOS NSView, Linux GtkWidget,
     * generic Windows HWND). Override when the embedded view is a
     * controller-style API (e.g. wry's WebView2) whose drawing rect is
     * decoupled from the platform HWND's window rect.
     */
    public fun setBounds(
        xPx: Int,
        yPx: Int,
        widthPx: Int,
        heightPx: Int,
    ) {}

    /**
     * Asks the view to release keyboard focus. Used when the host
     * window or a sibling Compose layer takes focus and the embedded
     * view should visually deselect.
     */
    public fun clearFocus() {}

    /**
     * Applies a uniform rounded-rectangle clip to the embedded view in
     * physical pixels. Default no-op — implementors override when the
     * platform host's generic clipping path (e.g. `SetWindowRgn` on
     * Windows, `CALayer.cornerRadius` on macOS) cannot reach the view's
     * rendered surface. The canonical case is Windows WebView2, which
     * paints via DirectComposition and ignores `SetWindowRgn`; the impl
     * applies the clip on its own DComp visual instead.
     *
     * Pass [Float.POSITIVE_INFINITY] for fully circular clipping;
     * the impl should cap at `min(w, h) / 2`.
     */
    public fun setCornerRadius(radiusPx: Float) {}

    /**
     * Final teardown. After this returns, the platform handle is no
     * longer accessed by Nucleus. Implementations should release any
     * native resources they own.
     */
    public fun dispose() {}

    /**
     * macOS variant — embedded **below** the Tao host's content view so
     * Compose can punch a transparent hole and draw on top (interop
     * blending). The [NativeView] `content` slot renders in the host
     * scene; overlapping siblings do too.
     */
    public interface NsView : NucleusPlatformView {
        /** Pointer to the user-supplied `NSView*` (top-bit clear). */
        public val nsViewHandle: Long
    }

    /**
     * Linux variant — embedded as a child of Tao's GTK content widget
     * via `gtk_container_add`. The implementor's `GtkWidget*` is
     * reparented under Tao's window, sized to the layout slot, and
     * rendered through GTK's normal cairo / GL paint pipeline. The
     * Compose surface composites on top with alpha; transparency in
     * the embedded rect lets the GTK widget show through.
     *
     * **Wayland only.** On the X11 backend (`NUCLEUS_TAO_LINUX_RENDERER=x11`,
     * or an actual X session) the show-through cannot work: the surface's
     * opaque-region carve-out is a Wayland protocol concept, and X11 never
     * blends a child window's alpha against its parent — the embedded
     * widget renders (and keeps running) behind the GL surface, but the
     * punched rect shows the desktop instead of the widget.
     */
    public interface GtkWidget : NucleusPlatformView {
        /**
         * Pointer to the user-supplied `GtkWidget*` (cast to Long). The app
         * owns a reference to it (`g_object_ref_sink`) for as long as the
         * handle is in use and releases it from [dispose]: the container's
         * unparent on detach drops the container's own reference, and a
         * widget nobody else holds is finalised right there.
         */
        public val gtkWidgetHandle: Long
    }

    /**
     * Windows variant — child HWND reparented under the Tao main HWND
     * via `SetParent`. A DirectComposition overlay composites the host
     * Compose scene over the embed so siblings and the `content` slot
     * draw on top (Win32 children always paint above their parent).
     */
    public interface HWnd : NucleusPlatformView {
        /** Pointer to the user-supplied `HWND` (cast to Long). */
        public val hwndHandle: Long
    }
}
