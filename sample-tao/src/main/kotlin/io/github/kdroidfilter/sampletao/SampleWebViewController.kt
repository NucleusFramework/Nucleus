package io.github.kdroidfilter.sampletao

import io.github.kdroidfilter.nucleus.core.runtime.Platform

/**
 * Backend-agnostic façade over the macOS WKWebView and Linux
 * WebKit2GTK helpers used by the sample-tao "WebView" tab.
 *
 * Lets [WebViewTab] reuse the same NavPill UI on both platforms
 * without sprinkling `Platform.Current ==` checks in the navigation
 * callbacks.
 */
internal interface SampleWebViewController {
    val handle: Long
    fun loadUrl(url: String)
    fun goBack()
    fun goForward()
    fun reload()
    fun canGoBack(): Boolean
    fun canGoForward(): Boolean
    fun currentUrl(): String?
    fun isLoading(): Boolean
    fun release()
}

internal class MacOsSampleWebViewController(override val handle: Long) :
    SampleWebViewController {
    override fun loadUrl(url: String) = SampleWebViewBridge.nativeLoadUrl(handle, url)
    override fun goBack() = SampleWebViewBridge.nativeGoBack(handle)
    override fun goForward() = SampleWebViewBridge.nativeGoForward(handle)
    override fun reload() = SampleWebViewBridge.nativeReload(handle)
    override fun canGoBack(): Boolean = SampleWebViewBridge.nativeCanGoBack(handle)
    override fun canGoForward(): Boolean = SampleWebViewBridge.nativeCanGoForward(handle)
    override fun currentUrl(): String? = SampleWebViewBridge.nativeCurrentUrl(handle)
    override fun isLoading(): Boolean = SampleWebViewBridge.nativeIsLoading(handle)
    override fun release() = SampleWebViewBridge.nativeRelease(handle)
}

internal class LinuxSampleWebViewController(override val handle: Long) :
    SampleWebViewController {
    override fun loadUrl(url: String) = SampleWebViewLinuxBridge.nativeLoadUrl(handle, url)
    override fun goBack() = SampleWebViewLinuxBridge.nativeGoBack(handle)
    override fun goForward() = SampleWebViewLinuxBridge.nativeGoForward(handle)
    override fun reload() = SampleWebViewLinuxBridge.nativeReload(handle)
    override fun canGoBack(): Boolean = SampleWebViewLinuxBridge.nativeCanGoBack(handle)
    override fun canGoForward(): Boolean = SampleWebViewLinuxBridge.nativeCanGoForward(handle)
    override fun currentUrl(): String? = SampleWebViewLinuxBridge.nativeCurrentUrl(handle)
    override fun isLoading(): Boolean = SampleWebViewLinuxBridge.nativeIsLoading(handle)
    override fun release() = SampleWebViewLinuxBridge.nativeRelease(handle)
}

internal fun isSampleWebViewSupported(): Boolean = when (Platform.Current) {
    Platform.MacOS -> SampleWebViewBridge.isLoaded
    Platform.Linux -> SampleWebViewLinuxBridge.isLoaded
    else -> false
}
