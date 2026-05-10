package io.github.kdroidfilter.sampletao

import io.github.kdroidfilter.nucleus.core.runtime.NativeLibraryLoader

internal object SampleWebViewBridge {
    private const val LIBRARY_NAME = "sample_tao_webview"

    val isLoaded: Boolean = NativeLibraryLoader.load(LIBRARY_NAME, SampleWebViewBridge::class.java)

    @JvmStatic
    external fun nativeCreate(): Long

    @JvmStatic
    external fun nativeLoadUrl(viewPtr: Long, url: String)

    @JvmStatic
    external fun nativeGoBack(viewPtr: Long)

    @JvmStatic
    external fun nativeGoForward(viewPtr: Long)

    @JvmStatic
    external fun nativeReload(viewPtr: Long)

    @JvmStatic
    external fun nativeCanGoBack(viewPtr: Long): Boolean

    @JvmStatic
    external fun nativeCanGoForward(viewPtr: Long): Boolean

    @JvmStatic
    external fun nativeCurrentUrl(viewPtr: Long): String?

    @JvmStatic
    external fun nativeIsLoading(viewPtr: Long): Boolean

    @JvmStatic
    external fun nativeRelease(viewPtr: Long)
}
