package dev.nucleusframework.nativeproxy

import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI

private const val TAG = "NativeProxySelector"

/**
 * A [ProxySelector] answering from the OS proxy configuration.
 *
 * Any URI the native backend cannot answer for — unsupported platform, missing
 * native library, non-TCP scheme — is delegated to [fallback] (usually the
 * selector that was the JDK default before installation), so installing this
 * selector never loses the `http.proxyHost` system properties.
 */
class NativeProxySelector internal constructor(
    private val fallback: ProxySelector?,
) : ProxySelector() {
    override fun select(uri: URI?): List<Proxy> {
        if (uri == null) return listOf(Proxy.NO_PROXY)
        if (!NativeProxy.isSupported) return fallback?.select(uri) ?: listOf(Proxy.NO_PROXY)

        val proxies = NativeProxy.proxiesFor(uri)
        if (proxies.isEmpty()) {
            // No OS proxy applies: an explicitly configured JDK proxy may still.
            val settings = NativeProxy.settings()
            return if (settings.isDirect) {
                fallback?.select(uri) ?: listOf(Proxy.NO_PROXY)
            } else {
                listOf(Proxy.NO_PROXY)
            }
        }

        // The JDK tries the returned proxies in order and falls through to DIRECT.
        return proxies.map { it.toJavaProxy() }
    }

    override fun connectFailed(
        uri: URI?,
        socketAddress: SocketAddress?,
        exception: IOException?,
    ) {
        val address = socketAddress as? InetSocketAddress
        debugln(TAG) { "Proxy connection failed for $uri via $address: ${exception?.message}" }
        fallback?.connectFailed(uri, socketAddress, exception)
    }
}
