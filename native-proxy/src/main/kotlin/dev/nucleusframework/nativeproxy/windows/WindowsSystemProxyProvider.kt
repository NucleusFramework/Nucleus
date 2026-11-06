package dev.nucleusframework.nativeproxy.windows

import dev.nucleusframework.nativeproxy.ProxyBypassRules
import dev.nucleusframework.nativeproxy.ProxyRules
import dev.nucleusframework.nativeproxy.ProxyServer
import dev.nucleusframework.nativeproxy.SystemProxyProvider
import dev.nucleusframework.nativeproxy.SystemProxySettings
import dev.nucleusframework.nativeproxy.debugln
import java.net.URI

private const val TAG = "WindowsSystemProxyProvider"

/**
 * Chromium's `ProxyConfigServiceWin` re-reads the configuration every 10 s on top of
 * its registry watch: settings written through `InternetSetOption` (WinINet, the
 * Settings app) update `Connections\DefaultConnectionSettings` without raising any
 * `RegNotifyChangeKeyValue` notification, so the watch alone misses them.
 */
private const val POLL_INTERVAL_MILLIS = 10_000

/**
 * Windows backend built on `WinHttpGetIEProxyConfigForCurrentUser`,
 * `WinHttpGetProxyForUrl` and `RegNotifyChangeKeyValue`.
 *
 * This is the same set of APIs Chromium's `ProxyConfigServiceWin` and
 * `ProxyResolverWinHttp` use, so the effective configuration matches what
 * Edge/Chrome see — including per-machine and Group Policy settings, which are
 * merged by WinHTTP itself.
 */
internal object WindowsSystemProxyProvider : SystemProxyProvider {
    override val isSupported: Boolean
        get() = WindowsProxyBridge.isLoaded

    override fun readSettings(): SystemProxySettings {
        val config = WindowsProxyBridge.getProxyConfig() ?: return SystemProxySettings.DIRECT

        val settings =
            SystemProxySettings(
                autoDetect = config.getOrNull(WindowsProxyBridge.INDEX_AUTO_DETECT) == "1",
                pacUrl = config.getOrNull(WindowsProxyBridge.INDEX_PAC_URL)?.takeIf { it.isNotBlank() },
                rules =
                    config
                        .getOrNull(WindowsProxyBridge.INDEX_PROXY)
                        ?.let(ProxyRules::parse) ?: ProxyRules.EMPTY,
                bypassRules =
                    config
                        .getOrNull(WindowsProxyBridge.INDEX_BYPASS)
                        ?.let(ProxyBypassRules::parse) ?: ProxyBypassRules.EMPTY,
            )

        debugln(TAG) { "Windows proxy configuration: $settings" }
        return settings
    }

    override fun resolveWithPacScript(
        uri: URI,
        settings: SystemProxySettings,
    ): List<ProxyServer>? {
        if (!settings.usesPacScript) return null
        // A configured script URL wins over WPAD, matching WinHTTP and Chromium.
        val resolved = WindowsProxyBridge.resolveProxyForUrl(uri.toString(), settings.pacUrl) ?: return null
        if (resolved.isEmpty()) return emptyList()
        return ProxyServer.parseList(resolved)
    }

    @Volatile
    private var lastPolled: SystemProxySettings? = null

    override fun awaitConfigurationChange(timeoutMillis: Int): Boolean {
        val baseline = lastPolled ?: readSettings().also { lastPolled = it }
        if (WindowsProxyBridge.waitForConfigChange(minOf(timeoutMillis, POLL_INTERVAL_MILLIS))) return true
        val current = readSettings()
        lastPolled = current
        return current != baseline
    }

    override fun wakeConfigurationWatcher() = WindowsProxyBridge.wakeWatcher()
}
