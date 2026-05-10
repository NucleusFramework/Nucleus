package io.github.kdroidfilter.nucleus.window.tao

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
 *    `GtkGLArea`, etc.). **No overlay slot** — the `content` lambda
 *    of `NativeView` is ignored on Linux.
 *  - [HWnd] on Windows — child HWND reparented under the Tao main HWND
 *    via `SetParent`, sized via `SetWindowPos`, clipped with
 *    `SetWindowRgn(CreateRoundRectRgn)` for rounded corners. Supports
 *    the [content] overlay slot via a sibling top-level WS_POPUP HWND
 *    with a transparent WGL context (see `NativeViewOverlayControllerWindows`).
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
     * Called with the embedded view's full bounds (position + size) in
     * physical pixels relative to the host window's client area. Default
     * is a no-op so most implementors can rely on the host's standard
     * `SetParent` + `SetWindowPos` (macOS NSView, Linux GtkWidget,
     * generic Windows HWND). Override when the embedded view is a
     * controller-style API (e.g. wry's WebView2) whose drawing rect is
     * decoupled from the platform HWND's window rect.
     */
    fun setBounds(xPx: Int, yPx: Int, widthPx: Int, heightPx: Int) {}

    /**
     * Asks the view to release keyboard focus. Used when the host
     * window or a sibling Compose layer takes focus and the embedded
     * view should visually deselect.
     */
    fun clearFocus() {}

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
    fun setCornerRadius(radiusPx: Float) {}

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
     * Linux variant — embedded as a child of Tao's GTK content widget
     * via `gtk_container_add`. The implementor's `GtkWidget*` is
     * reparented under Tao's window, sized to the layout slot, and
     * rendered through GTK's normal cairo / GL paint pipeline. The
     * Compose surface composites on top with alpha; transparency in
     * the embedded rect lets the GTK widget show through.
     */
    interface GtkWidget : NucleusPlatformView {
        /** Pointer to the user-supplied `GtkWidget*` (cast to Long). */
        val gtkWidgetHandle: Long
    }

    /**
     * Windows variant — child HWND reparented under the Tao main HWND
     * via `SetParent`, with the [content] overlay slot rendered in a
     * sibling top-level WS_POPUP HWND owning its own transparent WGL
     * context (see `NativeViewOverlayControllerWindows`).
     */
    interface HWnd : NucleusPlatformView {
        /** Pointer to the user-supplied `HWND` (cast to Long). */
        val hwndHandle: Long
    }
}
