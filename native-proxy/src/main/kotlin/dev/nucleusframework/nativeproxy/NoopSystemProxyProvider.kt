package dev.nucleusframework.nativeproxy

import java.net.URI

/**
 * No-op backend used on macOS and Linux.
 *
 * Reports an unsupported platform and a direct configuration, so
 * [NativeProxySelector] transparently delegates to the JDK default selector
 * (which already honours `http.proxyHost` and, on macOS/GNOME, the
 * `java.net.useSystemProxies` bridge).
 */
internal object NoopSystemProxyProvider : SystemProxyProvider {
    override val isSupported: Boolean = false

    override fun readSettings(): SystemProxySettings = SystemProxySettings.DIRECT

    override fun resolveWithPacScript(
        uri: URI,
        settings: SystemProxySettings,
    ): List<ProxyServer>? = null

    override fun awaitConfigurationChange(timeoutMillis: Int): Boolean = false

    override fun wakeConfigurationWatcher() = Unit
}
