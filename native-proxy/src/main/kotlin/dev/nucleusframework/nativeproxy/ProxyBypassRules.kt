package dev.nucleusframework.nativeproxy

import java.net.URI
import java.util.Locale

private const val LOCAL_TOKEN = "<local>"
private const val NEGATE_LOOPBACK_TOKEN = "<-loopback>"
private const val SCHEME_SEPARATOR = "://"
private const val DEFAULT_HTTP_PORT = 80
private const val DEFAULT_HTTPS_PORT = 443
private const val DEFAULT_FTP_PORT = 21

/**
 * The bypass list of a system proxy configuration.
 *
 * Semantics are ported from Chromium's `net::ProxyBypassRules`: hostname
 * patterns, CIDR blocks, the `<local>` token for dot-less hostnames, and the
 * implicit localhost / link-local bypass that `<-loopback>` turns off.
 */
data class ProxyBypassRules(
    val rules: List<BypassRule> = emptyList(),
    /** `<local>` — bypass hostnames without a dot (`intranet`, `build-server`). */
    val bypassesSimpleHostnames: Boolean = false,
    /** Localhost and link-local addresses always bypass the proxy unless `<-loopback>` is listed. */
    val bypassesImplicitLoopback: Boolean = true,
) {
    fun matches(uri: URI): Boolean {
        val host = uri.host ?: uri.schemeSpecificPart?.substringAfter("//")?.substringBefore('/') ?: return false
        val scheme = uri.scheme.orEmpty().lowercase(Locale.ROOT)
        val port = if (uri.port != -1) uri.port else defaultPortForScheme(scheme)
        return matches(scheme, host, port)
    }

    internal fun matches(
        scheme: String,
        rawHost: String,
        port: Int,
    ): Boolean {
        val host = rawHost.removeSurrounding("[", "]").lowercase(Locale.ROOT)
        if (bypassesImplicitLoopback && (isLocalhost(host) || isLinkLocal(host))) return true
        if (bypassesSimpleHostnames && !host.contains('.') && !isIpLiteral(host)) return true
        return rules.any { it.matches(scheme, host, port) }
    }

    companion object {
        val EMPTY = ProxyBypassRules()

        /**
         * Parses a WinInet bypass list — entries separated by `;` or whitespace.
         */
        fun parse(value: String): ProxyBypassRules {
            val rules = mutableListOf<BypassRule>()
            var simpleHostnames = false
            var implicitLoopback = true

            for (token in value.split(';', ' ', '\t', '\n', '\r')) {
                when (val entry = token.trim().lowercase(Locale.ROOT)) {
                    "" -> continue
                    LOCAL_TOKEN -> simpleHostnames = true
                    NEGATE_LOOPBACK_TOKEN -> implicitLoopback = false
                    else -> parseRule(entry)?.let(rules::add)
                }
            }

            return ProxyBypassRules(rules, simpleHostnames, implicitLoopback)
        }

        private fun parseRule(entry: String): BypassRule? {
            if (entry == "*") return BypassRule.MatchAll

            var rest = entry
            var scheme: String? = null
            val schemeEnd = rest.indexOf(SCHEME_SEPARATOR)
            if (schemeEnd >= 0) {
                scheme = rest.substring(0, schemeEnd)
                rest = rest.substring(schemeEnd + SCHEME_SEPARATOR.length)
                if (scheme == "*") scheme = null
            }
            if (rest.isEmpty()) return null

            if (rest.contains('/')) return parseIpBlock(rest, scheme)

            val (host, portText) = splitHostPort(rest) ?: return null
            if (host.isEmpty()) return null
            val port = portText?.toIntOrNull()
            if (portText != null && port == null) return null

            // Chromium rewrites a leading dot into a subdomain wildcard.
            val pattern = if (host.startsWith('.')) "*$host" else host
            return BypassRule.HostnamePattern(pattern, scheme, port)
        }

        private fun parseIpBlock(
            value: String,
            scheme: String?,
        ): BypassRule? {
            val prefixText = value.substringBefore('/')
            val bits = value.substringAfter('/').toIntOrNull() ?: return null
            if (bits < 0) return null
            val prefix = parseIpLiteral(prefixText) ?: return null
            return BypassRule.IpBlock(prefix, bits, scheme)
        }

        private fun defaultPortForScheme(scheme: String): Int =
            when (scheme) {
                "https", "wss" -> DEFAULT_HTTPS_PORT
                "ftp" -> DEFAULT_FTP_PORT
                else -> DEFAULT_HTTP_PORT
            }
    }
}
