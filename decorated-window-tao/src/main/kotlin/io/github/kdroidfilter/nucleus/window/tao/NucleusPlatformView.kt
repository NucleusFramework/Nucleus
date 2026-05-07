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
 *  - [HWnd] on Windows — child HWND embedding via `SetParent`. Not
 *    implemented yet; the variant exists so the API can ship cross-
 *    platform without later breaking changes.
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
     * Windows variant — child HWND attached via `SetParent`, with an
     * overlay HWND using `WS_EX_LAYERED | WS_EX_TRANSPARENT` for the
     * Compose `content` slot. **Not implemented yet** — the variant
     * exists so the API surface is forward-compatible.
     */
    interface HWnd : NucleusPlatformView {
        /** Pointer to the user-supplied `HWND` (cast to Long). */
        val hwndHandle: Long
    }
}
