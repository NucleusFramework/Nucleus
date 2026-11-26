package dev.nucleusframework.nativeproxy.windows

import dev.nucleusframework.core.runtime.NativeLibraryLoader
import dev.nucleusframework.nativeproxy.errorln
import java.util.logging.Level
import java.util.logging.Logger

private const val TAG = "WindowsProxyBridge"
private const val LIBRARY_NAME = "nucleus_proxy"

/**
 * JNI bridge over WinHTTP and the Internet Settings registry keys.
 *
 * Every entry point degrades to a neutral value when the native library could
 * not be loaded, so callers never have to guard the load state themselves.
 */
internal object WindowsProxyBridge {
    /** Index of the WinInet proxy string (`lpszProxy`) in the [nativeGetProxyConfig] result. */
    const val INDEX_PROXY = 0

    /** Index of the bypass list (`lpszProxyBypass`). */
    const val INDEX_BYPASS = 1

    /** Index of the PAC script URL (`lpszAutoConfigUrl`). */
    const val INDEX_PAC_URL = 2

    /** Index of the WPAD flag (`fAutoDetect`), `"1"` or `"0"`. */
    const val INDEX_AUTO_DETECT = 3

    private val logger = Logger.getLogger(WindowsProxyBridge::class.java.simpleName)
    private val loaded = NativeLibraryLoader.load(LIBRARY_NAME, WindowsProxyBridge::class.java)

    val isLoaded: Boolean get() = loaded

    /**
     * Returns `WinHttpGetIEProxyConfigForCurrentUser` as a 4-element array
     * indexed by the `INDEX_*` constants, or `null` when the call failed.
     */
    @JvmStatic
    external fun nativeGetProxyConfig(): Array<String?>?

    /**
     * Runs `WinHttpGetProxyForUrl` for [url].
     *
     * @param pacUrl an explicit PAC script URL, or `null` to use WPAD auto-detection.
     * @return the WinHTTP proxy list, an empty string when the script returned
     *     `DIRECT`, or `null` when the script could not be fetched or evaluated.
     */
    @JvmStatic
    external fun nativeResolveProxyForUrl(
        url: String,
        pacUrl: String?,
    ): String?

    /**
     * Blocks on registry change notifications for the Internet Settings keys and
     * returns true when one of them changed before [timeoutMillis] elapsed.
     */
    @JvmStatic
    external fun nativeWaitForConfigChange(timeoutMillis: Int): Boolean

    /** Signals the event [nativeWaitForConfigChange] also waits on. */
    @JvmStatic
    external fun nativeWakeWatcher()

    fun getProxyConfig(): Array<String?>? = call("nativeGetProxyConfig") { nativeGetProxyConfig() }

    fun resolveProxyForUrl(
        url: String,
        pacUrl: String?,
    ): String? = call("nativeResolveProxyForUrl") { nativeResolveProxyForUrl(url, pacUrl) }

    fun waitForConfigChange(timeoutMillis: Int): Boolean =
        call("nativeWaitForConfigChange") { nativeWaitForConfigChange(timeoutMillis) } ?: false

    fun wakeWatcher() {
        call("nativeWakeWatcher") { nativeWakeWatcher() }
    }

    private fun <T> call(
        name: String,
        block: () -> T,
    ): T? {
        if (!loaded) return null
        return try {
            block()
        } catch (e: UnsatisfiedLinkError) {
            logger.log(Level.WARNING, "JNI call failed for $name", e)
            errorln(TAG) { "Native proxy bridge unavailable: $name" }
            null
        }
    }
}
