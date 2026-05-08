package io.github.kdroidfilter.sampletao

import io.github.kdroidfilter.nucleus.core.runtime.NativeLibraryLoader

/**
 * JNI bridge to the C++ WebView2 wrapper at
 * `windows/sample_webview.cpp` — a direct
 * `CoreWebView2CompositionController` + DirectComposition implementation
 * for the sample-tao "WebView" tab demo.
 *
 * `nativeCreate(parentHwnd, initialUrl)` creates the WebView in a DComp
 * tree owned by us and returns an opaque handle. The WebView is
 * positioned and clipped via [nativeSetBounds] and [nativeSetCornerRadius]
 * — DComp clipping is what makes `cornerRadius` actually visible on
 * Windows (`SetWindowRgn` doesn't work because WebView2 paints via
 * DComp, bypassing the GDI region pipeline).
 *
 * WebView2 Runtime must be installed on the target machine (bundled
 * with Edge on modern Windows; install the Evergreen Bootstrapper if
 * missing).
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
        // The C++ side calls `LoadLibraryW("WebView2Loader.dll")` after
        // adding its own directory to the DLL search path; the loader
        // must therefore sit next to sample_tao_webview.dll in the
        // extracted cache directory.
        sidecarFiles = listOf("WebView2Loader.dll"),
    )

    /**
     * Creates a WebView2 instance attached to a DComp tree owned by the
     * native side, anchored on [parentHwnd]. Returns an opaque handle (NOT
     * an HWND — the WebView has no Win32 child HWND visible to callers).
     */
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
     * Sets the WebView's drawing rect. We position via the DComp visual's
     * offset (`SetOffsetX/Y`) and size the controller via `put_Bounds` in
     * controller-local coords (origin at the top-left of our root visual).
     * Coords are in physical pixels relative to the parent HWND's client area.
     */
    @JvmStatic
    external fun nativeSetBounds(handle: Long, xPx: Int, yPx: Int, widthPx: Int, heightPx: Int)

    /**
     * Applies a uniform rounded-rectangle clip on the WebView via DComp's
     * `IDCompositionRectangleClip`. Pass `0f` to remove. Cornerradius is
     * capped natively at `min(w, h) / 2` so callers can pass `+Inf` for
     * fully circular.
     */
    @JvmStatic
    external fun nativeSetCornerRadius(handle: Long, radiusPx: Float)
}
