package dev.nucleusframework.nativeproxy

import java.net.InetSocketAddress
import java.net.Proxy
import java.util.Locale

private const val MAX_PORT = 65535
private const val SCHEME_SEPARATOR = "://"

/**
 * A single proxy server: protocol, host and port.
 *
 * The host is never resolved by this library — [toJavaProxy] builds an
 * unresolved address so the proxy hostname is resolved by the connection
 * itself (and, for SOCKS, possibly by the proxy).
 */
data class ProxyServer(
    val protocol: ProxyProtocol,
    val host: String,
    val port: Int,
) {
    fun toJavaProxy(): Proxy = Proxy(protocol.javaProxyType, InetSocketAddress.createUnresolved(host, port))

    override fun toString(): String = "${protocol.uriScheme}://${formatHost()}:$port"

    private fun formatHost(): String = if (host.contains(':')) "[$host]" else host

    companion object {
        /**
         * Parses a proxy URI as written in a WinInet/PAC proxy list.
         *
         * Accepted forms: `host`, `host:port`, `[::1]:port`, `scheme://host:port`.
         * Returns `null` for `direct://`, unsupported schemes and malformed input.
         */
        fun parse(
            spec: String,
            defaultProtocol: ProxyProtocol = ProxyProtocol.HTTP,
        ): ProxyServer? {
            val trimmed = spec.trim()
            if (trimmed.isEmpty()) return null

            val schemeEnd = trimmed.indexOf(SCHEME_SEPARATOR)
            if (schemeEnd < 0) return parseAuthority(trimmed, defaultProtocol)

            val protocol = ProxyProtocol.fromUriScheme(trimmed.substring(0, schemeEnd)) ?: return null
            return parseAuthority(trimmed.substring(schemeEnd + SCHEME_SEPARATOR.length), protocol)
        }

        /**
         * Parses a semicolon- or whitespace-separated proxy list, as returned by
         * `WinHttpGetProxyForUrl` or found in a per-scheme WinInet proxy entry.
         */
        internal fun parseList(
            value: String,
            defaultProtocol: ProxyProtocol = ProxyProtocol.HTTP,
        ): List<ProxyServer> =
            value
                .split(';', ' ', '\t', '\n', '\r')
                .mapNotNull { entry ->
                    entry.takeIf { it.isNotBlank() }?.let { parse(it, defaultProtocol) }
                }

        private fun parseAuthority(
            authority: String,
            protocol: ProxyProtocol,
        ): ProxyServer? {
            // A PAC script may hand out a proxy with a trailing path; it is meaningless here.
            val value = authority.substringBefore('/').trim()
            if (value.isEmpty()) return null

            val (host, portText) = splitHostPort(value) ?: return null
            if (host.isEmpty()) return null

            val port = portText?.toIntOrNull() ?: protocol.defaultPort
            if (port !in 1..MAX_PORT) return null

            return ProxyServer(protocol, host.lowercase(Locale.ROOT), port)
        }
    }
}
