package dev.nucleusframework.nativeproxy

/**
 * A snapshot of the OS proxy configuration.
 *
 * Shaped after Chromium's `net::ProxyConfig`: WPAD auto-detection, an explicit
 * PAC script URL and static proxy servers are independent settings, and are
 * consulted in that order when resolving a URL.
 */
data class SystemProxySettings(
    /** WPAD is enabled ("Automatically detect settings"): a PAC script is discovered via DHCP/DNS. */
    val autoDetect: Boolean = false,
    /** An explicit PAC script URL ("Use automatic configuration script"). */
    val pacUrl: String? = null,
    /** Statically configured proxy servers. */
    val rules: ProxyRules = ProxyRules.EMPTY,
    /** Hosts that must be reached without a proxy. */
    val bypassRules: ProxyBypassRules = ProxyBypassRules.EMPTY,
) {
    /** Whether the configuration asks for direct connections only. */
    val isDirect: Boolean
        get() = !autoDetect && pacUrl == null && rules.isEmpty

    /** Whether resolving a URL requires running a PAC script. */
    val usesPacScript: Boolean
        get() = autoDetect || pacUrl != null

    companion object {
        val DIRECT = SystemProxySettings()
    }
}
