package dev.nucleusframework.nativeproxy.linux

import dev.nucleusframework.nativeproxy.BypassRule
import dev.nucleusframework.nativeproxy.ProxyBypassRules
import dev.nucleusframework.nativeproxy.ProxyProtocol
import dev.nucleusframework.nativeproxy.ProxyRules
import dev.nucleusframework.nativeproxy.ProxyServer
import dev.nucleusframework.nativeproxy.SystemProxySettings

/**
 * Reads the classic Unix proxy environment variables.
 *
 * Port of Chromium's `ProxyConfigServiceLinux::Delegate::GetConfigFromEnv`:
 * `auto_proxy`, `all_proxy`, `{http,https,ftp}_proxy`, `SOCKS_SERVER` /
 * `SOCKS_VERSION`, and `no_proxy`. Hostname bypass entries use suffix matching
 * (`google.com` also matches `www.google.com`).
 *
 * Returns `null` when no proxy-related variable is set, so the caller can
 * distinguish "nothing configured" from "explicitly direct" (`no_proxy=*` with
 * no proxy host).
 */
internal object EnvProxySettings {
    fun read(getenv: (String) -> String? = ::envLookup): SystemProxySettings? {
        val autoProxy = getenv("auto_proxy")
        if (autoProxy != null) {
            return if (autoProxy.isBlank()) {
                SystemProxySettings(autoDetect = true)
            } else {
                SystemProxySettings(pacUrl = autoProxy.trim())
            }
        }

        val allProxy = getenv("all_proxy")?.takeIf { it.isNotBlank() }
        val httpProxy = getenv("http_proxy")?.takeIf { it.isNotBlank() }
        val httpsProxy = getenv("https_proxy")?.takeIf { it.isNotBlank() }
        val ftpProxy = getenv("ftp_proxy")?.takeIf { it.isNotBlank() }
        val socksServer = getenv("SOCKS_SERVER")?.takeIf { it.isNotBlank() }
        val noProxy = getenv("no_proxy").orEmpty()

        val rules =
            buildRules(allProxy, httpProxy, httpsProxy, ftpProxy, socksServer, getenv)
                ?: return null

        if (rules.isEmpty) {
            return if (noProxy.isNotBlank()) SystemProxySettings.DIRECT else null
        }

        val bypass =
            if (noProxy.isBlank()) {
                ProxyBypassRules.EMPTY
            } else {
                ProxyBypassRules
                    .parse(noProxy.replace(',', ';'))
                    .withSuffixMatching()
            }

        return SystemProxySettings(rules = rules, bypassRules = bypass)
    }

    private fun buildRules(
        allProxy: String?,
        httpProxy: String?,
        httpsProxy: String?,
        ftpProxy: String?,
        socksServer: String?,
        getenv: (String) -> String?,
    ): ProxyRules? {
        if (allProxy != null) {
            val server = parseEnvProxy(allProxy, ProxyProtocol.HTTP) ?: return null
            return ProxyRules(singleProxies = listOf(server))
        }
        if (httpProxy != null || httpsProxy != null || ftpProxy != null) {
            return ProxyRules(
                proxiesForHttp = parseList(httpProxy, ProxyProtocol.HTTP),
                proxiesForHttps = parseList(httpsProxy, ProxyProtocol.HTTP),
                proxiesForFtp = parseList(ftpProxy, ProxyProtocol.HTTP),
            )
        }
        if (socksServer != null) {
            val scheme =
                if (getenv("SOCKS_VERSION") == "4") {
                    ProxyProtocol.SOCKS4
                } else {
                    ProxyProtocol.SOCKS5
                }
            val server = parseEnvProxy(socksServer, scheme) ?: return null
            return ProxyRules(singleProxies = listOf(server))
        }
        return ProxyRules.EMPTY
    }

    private fun parseList(
        value: String?,
        protocol: ProxyProtocol,
    ): List<ProxyServer> =
        value
            ?.let { parseEnvProxy(it, protocol) }
            ?.let(::listOf)
            .orEmpty()

    private fun envLookup(name: String): String? = System.getenv(name) ?: System.getenv(name.uppercase())

    private fun parseEnvProxy(
        value: String,
        defaultProtocol: ProxyProtocol,
    ): ProxyServer? {
        var host = value.trim()
        if (host.isEmpty()) return null

        val at = host.lastIndexOf('@')
        if (at >= 0) host = host.substring(at + 1)

        if (host.endsWith('/')) host = host.dropLast(1)

        return ProxyServer.parse(host, defaultProtocol)
    }
}

/**
 * Chromium rewrites env-var bypass hostnames into suffix matches so that a
 * rule of `google.com` also matches `www.google.com`. GNOME ignore-hosts does
 * not do this; only the env-var path applies it.
 */
internal fun ProxyBypassRules.withSuffixMatching(): ProxyBypassRules {
    if (rules.isEmpty()) return this
    val rewritten =
        rules.map { rule ->
            when (rule) {
                is BypassRule.HostnamePattern -> {
                    val pattern = rule.pattern
                    if (pattern.startsWith('*') || pattern.contains('/')) {
                        rule
                    } else {
                        rule.copy(pattern = "*$pattern")
                    }
                }
                else -> rule
            }
        }
    return copy(rules = rewritten)
}
