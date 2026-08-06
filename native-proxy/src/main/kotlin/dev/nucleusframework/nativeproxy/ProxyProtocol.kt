package dev.nucleusframework.nativeproxy

import java.net.Proxy
import java.util.Locale

private const val DEFAULT_HTTP_PORT = 80
private const val DEFAULT_HTTPS_PORT = 443
private const val DEFAULT_SOCKS_PORT = 1080

/**
 * Proxy protocols supported by the system proxy resolvers.
 *
 * Mirrors the schemes Chromium accepts in a proxy URI (`net::ProxyServer::Scheme`),
 * minus the ones the JDK cannot dial (`quic`).
 */
enum class ProxyProtocol(
    val uriScheme: String,
    val defaultPort: Int,
) {
    HTTP("http", DEFAULT_HTTP_PORT),
    HTTPS("https", DEFAULT_HTTPS_PORT),
    SOCKS4("socks4", DEFAULT_SOCKS_PORT),
    SOCKS5("socks5", DEFAULT_SOCKS_PORT),
    ;

    /** The [Proxy.Type] used when dialing through this protocol from the JDK. */
    val javaProxyType: Proxy.Type
        get() =
            when (this) {
                HTTP, HTTPS -> Proxy.Type.HTTP
                SOCKS4, SOCKS5 -> Proxy.Type.SOCKS
            }

    companion object {
        /**
         * Resolves a proxy URI scheme (the part before `://`) to a protocol.
         *
         * `socks` is an alias for SOCKS4, matching Chromium and WinInet.
         * Returns `null` for `direct` and for unsupported schemes.
         */
        fun fromUriScheme(scheme: String): ProxyProtocol? =
            when (scheme.lowercase(Locale.ROOT)) {
                "http" -> HTTP
                "https" -> HTTPS
                "socks", "socks4" -> SOCKS4
                "socks5" -> SOCKS5
                else -> null
            }
    }
}
