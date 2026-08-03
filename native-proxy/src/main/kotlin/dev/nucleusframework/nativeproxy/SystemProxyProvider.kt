package dev.nucleusframework.nativeproxy

import dev.nucleusframework.core.runtime.Platform
import dev.nucleusframework.nativeproxy.windows.WindowsSystemProxyProvider
import java.net.URI

/**
 * Platform backend reading — and watching — the OS proxy configuration.
 */
internal interface SystemProxyProvider {
    /** Whether this platform has a native implementation. */
    val isSupported: Boolean

    /** Reads the current configuration, [SystemProxySettings.DIRECT] when unavailable. */
    fun readSettings(): SystemProxySettings

    /**
     * Runs the PAC script (explicit URL or WPAD-discovered) for [uri].
     *
     * @return the resolved proxy list, empty for `DIRECT`, or `null` when the
     *     script could not be fetched or evaluated — callers then fall back to
     *     the static rules.
     */
    fun resolveWithPacScript(
        uri: URI,
        settings: SystemProxySettings,
    ): List<ProxyServer>?

    /**
     * Blocks until the OS proxy configuration changes or [timeoutMillis] elapses.
     *
     * @return true when a change was observed.
     */
    fun awaitConfigurationChange(timeoutMillis: Int): Boolean

    /** Unblocks a thread parked in [awaitConfigurationChange]. */
    fun wakeConfigurationWatcher()

    companion object {
        fun forCurrentPlatform(): SystemProxyProvider =
            when (Platform.Current) {
                Platform.Windows -> WindowsSystemProxyProvider
                Platform.MacOS, Platform.Linux, Platform.Unknown -> NoopSystemProxyProvider
            }
    }
}
