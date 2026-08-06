package dev.nucleusframework.nativeproxy.macos

import dev.nucleusframework.nativeproxy.ProxyBypassRules
import dev.nucleusframework.nativeproxy.ProxyRules
import dev.nucleusframework.nativeproxy.ProxyServer
import dev.nucleusframework.nativeproxy.SystemProxyProvider
import dev.nucleusframework.nativeproxy.SystemProxySettings
import dev.nucleusframework.nativeproxy.debugln
import java.net.URI

private const val TAG = "MacOsSystemProxyProvider"

/**
 * macOS backend built on `SCDynamicStoreCopyProxies` and
 * `CFNetworkExecuteProxyAutoConfigurationURL`.
 *
 * This is the same set of APIs Chromium's `ProxyConfigServiceMac` and
 * `ProxyResolverApple` use, so the effective configuration matches what
 * Safari/Chrome see — including per-interface and managed (MDM) settings,
 * which SystemConfiguration merges itself.
 *
 * Pure WPAD without an explicit PAC URL is reported via [SystemProxySettings.autoDetect]
 * but not evaluated: Apple embeds a discovered PAC URL into the system settings
 * when DHCP WPAD succeeds, so an empty [SystemProxySettings.pacUrl] almost
 * always means there is nothing to run. Callers fall back to the static rules.
 */
internal object MacOsSystemProxyProvider : SystemProxyProvider {
    override val isSupported: Boolean
        get() = MacOsProxyBridge.isLoaded

    override fun readSettings(): SystemProxySettings {
        val config = MacOsProxyBridge.getProxyConfig() ?: return SystemProxySettings.DIRECT

        val settings =
            SystemProxySettings(
                autoDetect = config.getOrNull(MacOsProxyBridge.INDEX_AUTO_DETECT) == "1",
                pacUrl = config.getOrNull(MacOsProxyBridge.INDEX_PAC_URL)?.takeIf { it.isNotBlank() },
                rules =
                    config
                        .getOrNull(MacOsProxyBridge.INDEX_PROXY)
                        ?.let(ProxyRules::parse) ?: ProxyRules.EMPTY,
                bypassRules =
                    config
                        .getOrNull(MacOsProxyBridge.INDEX_BYPASS)
                        ?.let(ProxyBypassRules::parse) ?: ProxyBypassRules.EMPTY,
            )

        debugln(TAG) { "macOS proxy configuration: $settings" }
        return settings
    }

    override fun resolveWithPacScript(
        uri: URI,
        settings: SystemProxySettings,
    ): List<ProxyServer>? {
        val pacUrl = settings.pacUrl ?: return null
        val resolved = MacOsProxyBridge.resolveProxyForUrl(uri.toString(), pacUrl) ?: return null
        if (resolved.isEmpty()) return emptyList()
        return ProxyServer.parseList(resolved)
    }

    override fun awaitConfigurationChange(timeoutMillis: Int): Boolean =
        MacOsProxyBridge.waitForConfigChange(timeoutMillis)

    override fun wakeConfigurationWatcher() = MacOsProxyBridge.wakeWatcher()
}
