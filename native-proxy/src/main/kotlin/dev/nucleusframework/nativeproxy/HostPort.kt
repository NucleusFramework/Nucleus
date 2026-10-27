package dev.nucleusframework.nativeproxy

/**
 * Splits a `host`, `host:port` or `[v6]:port` authority, returning the port as
 * raw text so callers decide whether a malformed port is fatal.
 *
 * A bare IPv6 literal without brackets carries no port: the last colon belongs
 * to the address.
 */
internal fun splitHostPort(value: String): Pair<String, String?>? {
    if (value.startsWith('[')) return splitBracketedHostPort(value)

    val colon = value.lastIndexOf(':')
    if (colon < 0 || value.indexOf(':') != colon) return value to null
    return value.substring(0, colon) to value.substring(colon + 1)
}

private fun splitBracketedHostPort(value: String): Pair<String, String?>? {
    val closing = value.indexOf(']')
    if (closing < 0) return null

    val host = value.substring(1, closing)
    val tail = value.substring(closing + 1)
    return when {
        tail.isEmpty() -> host to null
        tail.startsWith(':') -> host to tail.substring(1)
        else -> null
    }
}
