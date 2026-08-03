package dev.nucleusframework.nativeproxy

import java.net.InetAddress

/**
 * A single entry of a proxy bypass list.
 *
 * The variants mirror Chromium's `SchemeHostPortMatcherRule` implementations.
 */
sealed interface BypassRule {
    fun matches(
        scheme: String,
        host: String,
        port: Int,
    ): Boolean

    /** `*` — every URL bypasses the proxy. */
    data object MatchAll : BypassRule {
        override fun matches(
            scheme: String,
            host: String,
            port: Int,
        ): Boolean = true
    }

    /**
     * A hostname wildcard pattern, optionally restricted to a scheme and/or a port.
     *
     * `*` matches any run of characters and `?` a single one, so `*.corp.com`
     * matches `a.corp.com` but not `corp.com` — exactly as `base::MatchPattern`.
     */
    data class HostnamePattern(
        val pattern: String,
        val scheme: String? = null,
        val port: Int? = null,
    ) : BypassRule {
        private val regex = globToRegex(pattern)

        override fun matches(
            scheme: String,
            host: String,
            port: Int,
        ): Boolean {
            if (this.scheme != null && this.scheme != scheme) return false
            if (this.port != null && this.port != port) return false
            return regex.matches(host)
        }
    }

    /** An IP block in CIDR notation (`10.0.0.0/8`, `fe80::/10`). */
    data class IpBlock(
        val prefix: InetAddress,
        val prefixBits: Int,
        val scheme: String? = null,
    ) : BypassRule {
        override fun matches(
            scheme: String,
            host: String,
            port: Int,
        ): Boolean {
            if (this.scheme != null && this.scheme != scheme) return false
            val address = parseIpLiteral(host) ?: return false
            return matchesCidr(address, prefix, prefixBits)
        }
    }
}

private fun globToRegex(pattern: String): Regex {
    val builder = StringBuilder()
    for (char in pattern) {
        when (char) {
            '*' -> builder.append(".*")
            '?' -> builder.append('.')
            else -> builder.append(Regex.escape(char.toString()))
        }
    }
    return Regex(builder.toString())
}
