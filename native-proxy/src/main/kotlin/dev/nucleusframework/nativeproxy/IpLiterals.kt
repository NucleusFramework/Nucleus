package dev.nucleusframework.nativeproxy

import java.net.InetAddress
import java.net.UnknownHostException
import java.util.Locale

private const val IPV4_LOOPBACK_PREFIX = "127."
private const val LINK_LOCAL_IPV4_PREFIX = "169.254."
private const val BITS_PER_BYTE = 8
private const val BYTE_MASK = 0xFF

private val IPV4_PATTERN = Regex("""^\d{1,3}(\.\d{1,3}){3}$""")

/**
 * Parses [host] as an IP literal without ever hitting DNS.
 *
 * [InetAddress.getByName] resolves anything that is not a literal, so the shape
 * is checked first and only literals are handed over.
 */
internal fun parseIpLiteral(host: String): InetAddress? {
    val candidate = host.removeSurrounding("[", "]")
    val looksNumeric = IPV4_PATTERN.matches(candidate) || candidate.contains(':')
    if (!looksNumeric) return null
    return try {
        InetAddress.getByName(candidate)
    } catch (_: UnknownHostException) {
        null
    }
}

internal fun isIpLiteral(host: String): Boolean = parseIpLiteral(host) != null

/**
 * Whether [host] designates the local machine.
 *
 * Same set as Chromium's `net::IsLocalhost`: the `localhost` family (including
 * subdomains and the trailing-dot form), 127.0.0.0/8 and `::1`.
 */
internal fun isLocalhost(host: String): Boolean {
    val name = host.lowercase(Locale.ROOT).removeSuffix(".")
    if (name == "localhost" || name.endsWith(".localhost")) return true
    if (name == "localhost6" || name == "localhost6.localdomain6") return true
    if (name.startsWith(IPV4_LOOPBACK_PREFIX)) return isIpLiteral(name)
    val address = parseIpLiteral(name) ?: return false
    return address.isLoopbackAddress
}

/**
 * Whether [host] is a link-local address (169.254.0.0/16 or fe80::/10).
 *
 * Chromium bypasses these implicitly together with localhost.
 */
internal fun isLinkLocal(host: String): Boolean {
    if (host.startsWith(LINK_LOCAL_IPV4_PREFIX) && isIpLiteral(host)) return true
    val address = parseIpLiteral(host) ?: return false
    return address.isLinkLocalAddress
}

/** Whether [address] falls inside the CIDR block `[prefix]/[prefixBits]`. */
internal fun matchesCidr(
    address: InetAddress,
    prefix: InetAddress,
    prefixBits: Int,
): Boolean {
    val addressBytes = address.address
    val prefixBytes = prefix.address
    if (addressBytes.size != prefixBytes.size) return false
    if (prefixBits > addressBytes.size * BITS_PER_BYTE) return false

    val fullBytes = prefixBits / BITS_PER_BYTE
    for (i in 0 until fullBytes) {
        if (addressBytes[i] != prefixBytes[i]) return false
    }

    val remainingBits = prefixBits % BITS_PER_BYTE
    if (remainingBits == 0) return true

    val mask = (BYTE_MASK shl (BITS_PER_BYTE - remainingBits)) and BYTE_MASK
    return (addressBytes[fullBytes].toInt() and mask) == (prefixBytes[fullBytes].toInt() and mask)
}
