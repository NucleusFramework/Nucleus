package dev.nucleusframework.window.tao

/**
 * Public factories for [NucleusPlatformView].
 *
 * [NucleusPlatformView] is a `sealed interface`, so external modules cannot
 * implement it directly. These factories let any consumer (e.g. an
 * out-of-tree WebView library) supply a platform view to [NativeView] by
 * passing a native handle plus optional lifecycle/geometry callbacks.
 *
 * The [handle] lambda is queried lazily each time the host needs the native
 * pointer, so backends whose handle becomes available only after creation
 * (or is intentionally null, e.g. a WebView2 DirectComposition controller)
 * are supported. The `onSetBounds` / `onSetCornerRadius` callbacks are the
 * hook controller-style backends use to drive their own surface — see the
 * KDoc on [NucleusPlatformView.setBounds] / [NucleusPlatformView.setCornerRadius].
 */

/** macOS — embeds an `NSView*` returned by [handle]. */
fun nucleusNsPlatformView(
    handle: () -> Long,
    onResize: (widthPx: Int, heightPx: Int) -> Unit = { _, _ -> },
    onSetBounds: (xPx: Int, yPx: Int, widthPx: Int, heightPx: Int) -> Unit = { _, _, _, _ -> },
    onSetCornerRadius: (radiusPx: Float) -> Unit = {},
    onClearFocus: () -> Unit = {},
    onDispose: () -> Unit = {},
): NucleusPlatformView.NsView = object : NucleusPlatformView.NsView {
    override val nsViewHandle: Long get() = handle()
    override fun resize(widthPx: Int, heightPx: Int) = onResize(widthPx, heightPx)
    override fun setBounds(xPx: Int, yPx: Int, widthPx: Int, heightPx: Int) =
        onSetBounds(xPx, yPx, widthPx, heightPx)
    override fun setCornerRadius(radiusPx: Float) = onSetCornerRadius(radiusPx)
    override fun clearFocus() = onClearFocus()
    override fun dispose() = onDispose()
}

/** Windows — embeds an `HWND` returned by [handle] (may be 0 for DComp backends). */
fun nucleusHwndPlatformView(
    handle: () -> Long,
    onResize: (widthPx: Int, heightPx: Int) -> Unit = { _, _ -> },
    onSetBounds: (xPx: Int, yPx: Int, widthPx: Int, heightPx: Int) -> Unit = { _, _, _, _ -> },
    onSetCornerRadius: (radiusPx: Float) -> Unit = {},
    onClearFocus: () -> Unit = {},
    onDispose: () -> Unit = {},
): NucleusPlatformView.HWnd = object : NucleusPlatformView.HWnd {
    override val hwndHandle: Long get() = handle()
    override fun resize(widthPx: Int, heightPx: Int) = onResize(widthPx, heightPx)
    override fun setBounds(xPx: Int, yPx: Int, widthPx: Int, heightPx: Int) =
        onSetBounds(xPx, yPx, widthPx, heightPx)
    override fun setCornerRadius(radiusPx: Float) = onSetCornerRadius(radiusPx)
    override fun clearFocus() = onClearFocus()
    override fun dispose() = onDispose()
}

/** Linux — embeds a `GtkWidget*` returned by [handle]. */
fun nucleusGtkPlatformView(
    handle: () -> Long,
    onResize: (widthPx: Int, heightPx: Int) -> Unit = { _, _ -> },
    onSetBounds: (xPx: Int, yPx: Int, widthPx: Int, heightPx: Int) -> Unit = { _, _, _, _ -> },
    onSetCornerRadius: (radiusPx: Float) -> Unit = {},
    onClearFocus: () -> Unit = {},
    onDispose: () -> Unit = {},
): NucleusPlatformView.GtkWidget = object : NucleusPlatformView.GtkWidget {
    override val gtkWidgetHandle: Long get() = handle()
    override fun resize(widthPx: Int, heightPx: Int) = onResize(widthPx, heightPx)
    override fun setBounds(xPx: Int, yPx: Int, widthPx: Int, heightPx: Int) =
        onSetBounds(xPx, yPx, widthPx, heightPx)
    override fun setCornerRadius(radiusPx: Float) = onSetCornerRadius(radiusPx)
    override fun clearFocus() = onClearFocus()
    override fun dispose() = onDispose()
}
