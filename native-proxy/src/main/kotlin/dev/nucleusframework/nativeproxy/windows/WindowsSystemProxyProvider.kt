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

    override fun awaitConfigurationChange(timeoutMillis: Int): Boolean =
        WindowsProxyBridge.waitForConfigChange(timeoutMillis)

    override fun wakeConfigurationWatcher() = WindowsProxyBridge.wakeWatcher()
}
