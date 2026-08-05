package dev.nucleusframework.nativeproxy.linux

import dev.nucleusframework.core.runtime.NativeLibraryLoader
import dev.nucleusframework.nativeproxy.errorln
import java.util.logging.Level
import java.util.logging.Logger

private const val TAG = "LinuxProxyBridge"
private const val LIBRARY_NAME = "nucleus_proxy"

/**
 * JNI bridge over GNOME GSettings (`org.gnome.system.proxy`).
 *
 * Every entry point degrades to a neutral value when the native library could
 * not be loaded or the schema is missing, so callers never have to guard the
 * load state themselves.
 */
internal object LinuxProxyBridge {
    /** Index of the WinInet-style proxy string in the [nativeGetProxyConfig] result. */
    const val INDEX_PROXY = 0

    /** Index of the `;`-joined ignore-hosts list. */
    const val INDEX_BYPASS = 1

    /** Index of the PAC script URL (`autoconfig-url`). */
    const val INDEX_PAC_URL = 2

    /** Index of the WPAD flag, `"1"` or `"0"`. */
    const val INDEX_AUTO_DETECT = 3

    private val logger = Logger.getLogger(LinuxProxyBridge::class.java.simpleName)
    private val loaded = NativeLibraryLoader.load(LIBRARY_NAME, LinuxProxyBridge::class.java)

    val isLoaded: Boolean get() = loaded

    /**
     * Returns the GSettings proxy configuration as a 4-element array indexed by
     * the `INDEX_*` constants, or `null` when GIO / the schema is unavailable.
     */
    @JvmStatic
    external fun nativeGetProxyConfig(): Array<String?>?

    /**
     * Blocks until a GSettings proxy key changes or [timeoutMillis] elapses.
     *
     * @return true when a change was observed.
     */
    @JvmStatic
    external fun nativeWaitForConfigChange(timeoutMillis: Int): Boolean

    /** Signals the event [nativeWaitForConfigChange] also waits on. */
    @JvmStatic
    external fun nativeWakeWatcher()

    fun getProxyConfig(): Array<String?>? = call("nativeGetProxyConfig") { nativeGetProxyConfig() }

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
