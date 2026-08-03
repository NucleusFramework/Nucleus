package dev.nucleusframework.nativeproxy

import java.util.Locale

/**
 * The static proxy servers of a system configuration, either a single list used
 * for every URL scheme or one list per scheme.
 *
 * Layout and lookup semantics mirror Chromium's `net::ProxyConfig::ProxyRules`.
 */
data class ProxyRules(
    val singleProxies: List<ProxyServer> = emptyList(),
    val proxiesForHttp: List<ProxyServer> = emptyList(),
    val proxiesForHttps: List<ProxyServer> = emptyList(),
    val proxiesForFtp: List<ProxyServer> = emptyList(),
    /** Used for every scheme without a dedicated list — the WinInet `socks=` entry. */
    val fallbackProxies: List<ProxyServer> = emptyList(),
) {
    val isEmpty: Boolean
        get() =
            singleProxies.isEmpty() &&
                proxiesForHttp.isEmpty() &&
                proxiesForHttps.isEmpty() &&
                proxiesForFtp.isEmpty() &&
                fallbackProxies.isEmpty()

    /**
     * The proxies to try for a URL of [urlScheme], empty meaning a direct connection.
     *
     * A scheme without a dedicated list falls back to [fallbackProxies], which is
     * how WinInet treats its `socks=` entry.
     */
    fun proxiesForUrlScheme(urlScheme: String): List<ProxyServer> {
        if (singleProxies.isNotEmpty()) return singleProxies
        return when (urlScheme.lowercase(Locale.ROOT)) {
            "http" -> proxiesForHttp
            "https", "wss" -> proxiesForHttps
            "ftp" -> proxiesForFtp
            else -> fallbackProxies
        }.ifEmpty { fallbackProxies }
    }

    companion object {
        val EMPTY = ProxyRules()

        /**
         * Parses a WinInet proxy string.
         *
         * Two shapes are accepted, as in Chromium's `ProxyRules::ParseFromString`:
         * a bare list (`host:port`, applied to every scheme) and a per-scheme list
         * (`http=host:port;https=host:port;socks=host:1080`). Entries without a
         * scheme prefix default to HTTP, except `socks=` which defaults to SOCKS4.
         */
        fun parse(value: String): ProxyRules {
            val entries =
                value
                    .split(';')
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
            if (entries.isEmpty()) return EMPTY

            val perScheme = entries.any { it.contains('=') }
            if (!perScheme) {
                return ProxyRules(singleProxies = entries.mapNotNull { ProxyServer.parse(it) })
            }

            val lists = mutableMapOf<String, List<ProxyServer>>()
            for (entry in entries) {
                val separator = entry.indexOf('=')
                if (separator < 0) continue
                val scheme = entry.substring(0, separator).trim().lowercase(Locale.ROOT)
                val servers = entry.substring(separator + 1).trim()
                val default = if (scheme == "socks") ProxyProtocol.SOCKS4 else ProxyProtocol.HTTP
                val parsed = ProxyServer.parseList(servers, default)
                if (parsed.isNotEmpty()) {
                    lists[scheme] = lists.getOrElse(scheme) { emptyList() } + parsed
                }
            }

            return ProxyRules(
                proxiesForHttp = lists["http"].orEmpty(),
                proxiesForHttps = lists["https"].orEmpty(),
                proxiesForFtp = lists["ftp"].orEmpty(),
                fallbackProxies = lists["socks"].orEmpty(),
            )
        }
    }
}
