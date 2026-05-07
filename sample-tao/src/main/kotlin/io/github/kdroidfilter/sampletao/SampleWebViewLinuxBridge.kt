package io.github.kdroidfilter.sampletao

import io.github.kdroidfilter.nucleus.core.runtime.NativeLibraryLoader

/**
 * JNI bridge to `linux/sample_webview.c`. Linux equivalent of the
 * macOS [SampleWebViewBridge] — the create call returns a
 * `WebKitWebView*` (which is also a `GtkWidget*`) ready to be
 * embedded via `NucleusPlatformView.GtkWidget`.
 */
internal object SampleWebViewLinuxBridge {
    private const val LIBRARY_NAME = "sample_tao_webview_linux"

    val isLoaded: Boolean = NativeLibraryLoader.load(
        LIBRARY_NAME,
        SampleWebViewLinuxBridge::class.java,
    )

    @JvmStatic
    external fun nativeCreate(): Long

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
}
