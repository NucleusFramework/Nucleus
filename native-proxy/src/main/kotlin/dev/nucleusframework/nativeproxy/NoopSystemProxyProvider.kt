package dev.nucleusframework.nativeproxy

import java.net.URI

/**
 * No-op backend used on unknown platforms.
 *
 * Reports an unsupported platform and a direct configuration, so
 * [NativeProxySelector] transparently delegates to the JDK default selector
 * (which already honours `http.proxyHost` / `https.proxyHost`).
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
