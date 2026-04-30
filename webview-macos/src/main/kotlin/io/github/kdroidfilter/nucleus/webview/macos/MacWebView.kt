package io.github.kdroidfilter.nucleus.webview.macos

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.window.FrameWindowScope
import javax.swing.JComponent
import javax.swing.JFrame

/**
 * Hosts a system WKWebView under the current Compose Desktop window with
 * Compose UI rendered as overlays on top.
 *
 * Designed to drop into a `DecoratedWindow { ... }` from `decorated-window-jni`
 * without any extra wiring at the call site. The composable retrofits the
 * underlying JFrame / SkiaLayer / NSWindow / AWTView so that:
 *   - JFrame's content/root pane no longer paints an opaque background
 *   - Compose's SkiaLayer is forced to `transparency = true` reflectively,
 *     bypassing the "must be undecorated" check
 *   - AWTView's metal layer is alpha-aware so siblings render through
 *   - A real WKWebView NSView is attached as sibling of AWTView
 *   - AWTView's `hitTest:` is swizzled so events reach the WebView in the
 *     region tracked from Kotlin, while clicks on Compose UI overlays still
 *     reach Compose
 *
 * The native title bar appearance is preserved (AppKit's traffic lights and
 * the DecoratedWindow's Compose-rendered title bar are unaffected).
 */
@Composable
fun FrameWindowScope.MacWebView(
    url: String,
    modifier: Modifier = Modifier,
    state: MacWebViewState = rememberMacWebViewState(),
) {
    val density = LocalDensity.current

    DisposableEffect(window) {
        WebViewWindowConfigurator.configure(window)
        if (NativeWebViewBridge.isLoaded) {
            val nsWindowPtr = NativeWebViewBridge.nativeGetNSWindowPtr(window)
            if (nsWindowPtr != 0L) {
                NativeWebViewBridge.nativeConfigureWindowForOverlay(nsWindowPtr)
                val handle = NativeWebViewBridge.nativeCreate(nsWindowPtr)
                state.handle = handle
                if (handle != 0L) {
                    NativeWebViewBridge.setLoadingListener(handle) { state.setLoading(it) }
                }
            }
        }
        onDispose {
            val handle = state.handle
            if (handle != 0L) {
                NativeWebViewBridge.removeLoadingListener(handle)
                NativeWebViewBridge.nativeDestroy(handle)
                state.handle = 0L
                state.setLoading(false)
            }
        }
    }

    LaunchedEffect(state.handle, url) {
        val handle = state.handle
        if (handle != 0L && url.isNotEmpty()) {
            NativeWebViewBridge.nativeLoadUrl(handle, url)
        }
    }

    Box(
        modifier = modifier.onGloballyPositioned { coordinates ->
            val handle = state.handle
            if (handle == 0L) return@onGloballyPositioned
            val bounds = coordinates.boundsInWindow()
            val scale = density.density.toDouble()
            NativeWebViewBridge.nativeSetFrame(
                handle = handle,
                x = bounds.left / scale,
                yFromTop = bounds.top / scale,
                width = bounds.width / scale,
                height = bounds.height / scale,
            )
        },
    )
}

@Stable
class MacWebViewState internal constructor() {
    internal var handle: Long = 0L
    private val _isLoading = mutableStateOf(false)

    /** True while a navigation is in progress. Updated from WKNavigationDelegate. */
    val isLoading: State<Boolean> get() = _isLoading

    internal fun setLoading(loading: Boolean) { _isLoading.value = loading }

    fun reload() { if (handle != 0L) NativeWebViewBridge.nativeReload(handle) }
    fun goBack() { if (handle != 0L) NativeWebViewBridge.nativeGoBack(handle) }
    fun goForward() { if (handle != 0L) NativeWebViewBridge.nativeGoForward(handle) }
    fun loadUrl(url: String) { if (handle != 0L) NativeWebViewBridge.nativeLoadUrl(handle, url) }
    fun loadHtml(html: String, baseUrl: String? = null) {
        if (handle != 0L) NativeWebViewBridge.nativeLoadHtml(handle, html, baseUrl)
    }
    fun setHidden(hidden: Boolean) {
        if (handle != 0L) NativeWebViewBridge.nativeSetHidden(handle, hidden)
    }
}

@Composable
fun rememberMacWebViewState(): MacWebViewState = remember { MacWebViewState() }

// ─── Internal: window-level configuration done once per window ────────────────

private object WebViewWindowConfigurator {
    private val configured = java.util.WeakHashMap<JFrame, Boolean>()

    fun configure(frame: JFrame) {
        synchronized(configured) {
            if (configured[frame] == true) return
            configured[frame] = true
        }
        // Setting isOpaque = false AND a transparent background colour on
        // rootPane / contentPane suppresses AWT's opaque background fill on
        // both JVM and GraalVM native-image.
        // Note: we deliberately do NOT write the frame's own `background`
        // field reflectively — on native-image that ends up baked through
        // Java2D and renders the whole window as opaque black.
        val transparent = java.awt.Color(0, 0, 0, 0)
        runCatching {
            frame.rootPane?.let {
                it.isOpaque = false
                it.background = transparent
            }
            (frame.contentPane as? JComponent)?.let {
                it.isOpaque = false
                it.background = transparent
            }
        }
        forceSkiaLayerTransparency(frame)
    }

    private fun forceSkiaLayerTransparency(root: java.awt.Component) {
        fun walk(c: java.awt.Component) {
            var cls: Class<*>? = c.javaClass
            while (cls != null && cls != java.awt.Component::class.java) {
                try {
                    val m = cls.getDeclaredMethod("setTransparency", Boolean::class.javaPrimitiveType)
                    m.isAccessible = true
                    m.invoke(c, true)
                    return
                } catch (_: NoSuchMethodException) {
                    cls = cls.superclass
                } catch (_: Throwable) {
                    return
                }
            }
            if (c is java.awt.Container) {
                for (child in c.components) walk(child)
            }
        }
        walk(root)
    }
}
