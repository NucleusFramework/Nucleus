package io.github.kdroidfilter.sampletao

import io.github.kdroidfilter.nucleus.core.runtime.NativeLibraryLoader

/**
 * JNI bridge to `windows-rs/src/lib.rs` — a `wry`-backed real WebView2
 * instance for the sample-tao "WebView" tab demo.
 *
 * `nativeCreate(parentHwnd, initialUrl)` creates the WebView as a
 * child of the Tao main HWND and returns the hosting HWND that
 * `NativeView` can reparent into its own bounds. WebView2 Runtime
 * must be installed on the target machine (bundled with Edge on
 * modern Windows; install the Evergreen Bootstrapper if missing).
 *
 * Threading: every entry point must run on the Tao main thread (=
 * the thread that owns `parentHwnd`). WebView2's controller is STA,
 * so off-thread access deadlocks.
 */
internal object SampleWebViewWindowsBridge {
    private const val LIBRARY_NAME = "sample_tao_webview"

    val isLoaded: Boolean = NativeLibraryLoader.load(
        LIBRARY_NAME,
        SampleWebViewWindowsBridge::class.java,
    )

    /** Creates a wry WebView under [parentHwnd] and returns its hosting HWND. */
    @JvmStatic
    external fun nativeCreate(parentHwnd: Long, initialUrl: String): Long

    @JvmStatic
    external fun nativeRelease(handle: Long)

    @JvmStatic
    external fun nativeLoadUrl(handle: Long, url: String)

    @JvmStatic
    external fun nativeGoBack(handle: Long)

    @JvmStatic
    external fun nativeGoForward(handle: Long)

    @JvmStatic
    external fun nativeReload(handle: Long)

    @JvmStatic
    external fun nativeCanGoBack(handle: Long): Boolean

    @JvmStatic
    external fun nativeCanGoForward(handle: Long): Boolean

    @JvmStatic
    external fun nativeCurrentUrl(handle: Long): String?

    @JvmStatic
    external fun nativeIsLoading(handle: Long): Boolean

    /**
     * Sets the WebView2 controller's drawing rect inside the parent
     * HWND's client area. wry on Windows attaches the controller
     * directly to the parent HWND (no intermediate hosting HWND), so
     * the controller's `put_Bounds(x, y, w, h)` is what positions the
     * WebView visually — not `SetWindowPos` on the returned HWND.
     */
    @JvmStatic
    external fun nativeSetBounds(handle: Long, xPx: Int, yPx: Int, widthPx: Int, heightPx: Int)
}
