package io.github.kdroidfilter.nucleus.webview.macos

import io.github.kdroidfilter.nucleus.core.runtime.NativeLibraryLoader
import java.awt.Window
import java.util.concurrent.ConcurrentHashMap
import javax.swing.SwingUtilities

private const val LIBRARY_NAME = "nucleus_webview_macos"

internal object NativeWebViewBridge {
    private val loaded = NativeLibraryLoader.load(LIBRARY_NAME, NativeWebViewBridge::class.java)

    val isLoaded: Boolean get() = loaded

    /** Per-handle list of loading-state listeners, populated by [MacWebViewState]. */
    private val loadingListeners = ConcurrentHashMap<Long, (Boolean) -> Unit>()

    fun setLoadingListener(handle: Long, listener: (Boolean) -> Unit) {
        loadingListeners[handle] = listener
    }

    fun removeLoadingListener(handle: Long) {
        loadingListeners.remove(handle)
    }

    /** Called from native via JNI when WKNavigationDelegate fires. */
    @JvmStatic
    fun onLoadingStateChanged(handle: Long, isLoading: Boolean) {
        val listener = loadingListeners[handle] ?: return
        SwingUtilities.invokeLater { listener(isLoading) }
    }

    @JvmStatic external fun nativeGetNSWindowPtr(window: Window): Long

    @JvmStatic external fun nativeConfigureWindowForOverlay(nsWindowPtr: Long)

    @JvmStatic external fun nativeCreate(nsWindowPtr: Long): Long

    @JvmStatic external fun nativeDestroy(handle: Long)

    @JvmStatic external fun nativeSetFrame(
        handle: Long,
        x: Double,
        yFromTop: Double,
        width: Double,
        height: Double,
    )

    @JvmStatic external fun nativeLoadUrl(handle: Long, url: String)

    @JvmStatic external fun nativeLoadHtml(handle: Long, html: String, baseUrl: String?)

    @JvmStatic external fun nativeReload(handle: Long)

    @JvmStatic external fun nativeGoBack(handle: Long)

    @JvmStatic external fun nativeGoForward(handle: Long)

    @JvmStatic external fun nativeSetHidden(handle: Long, hidden: Boolean)
}
