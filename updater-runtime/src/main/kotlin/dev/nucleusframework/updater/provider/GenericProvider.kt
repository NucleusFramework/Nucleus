package dev.nucleusframework.updater.provider

import dev.nucleusframework.core.runtime.Platform
import java.net.URI

public class GenericProvider(
    public val baseUrl: String,
) : UpdateProvider {
    private val normalizedBaseUrl = baseUrl.trimEnd('/')

    init {
        requireSecureBaseUrl(baseUrl)
    }

    override fun getUpdateMetadataUrl(
        channel: String,
        platform: Platform,
    ): String {
        val suffix = platformSuffix(platform)
        val fileName = if (suffix.isEmpty()) "$channel.yml" else "$channel-$suffix.yml"
        return "$normalizedBaseUrl/$fileName"
    }

    override fun getDownloadUrl(
        fileName: String,
        version: String,
    ): String = "$normalizedBaseUrl/$fileName"

    private fun platformSuffix(platform: Platform): String =
        when (platform) {
            Platform.Windows -> ""
            Platform.MacOS -> "mac"
            Platform.Linux -> "linux"
            Platform.Unknown -> ""
        }
}

/**
 * Rejects a non-`https` update origin at construction time.
 *
 * The whole update is fetched from this base URL — manifest, checksums and the artifact.
 * Over plain `http` an on-path attacker can rewrite all three together, so the SHA-512 in the
 * manifest provides no protection (they control the manifest too). `http` is allowed only for
 * loopback hosts so local integration tests can serve fixtures without TLS.
 */
private fun requireSecureBaseUrl(baseUrl: String) {
    val uri =
        runCatching { URI(baseUrl) }.getOrElse {
            throw IllegalArgumentException("GenericProvider baseUrl is not a valid URL: $baseUrl", it)
        }
    val scheme = uri.scheme?.lowercase()
    require(scheme == "https" || (scheme == "http" && isLoopbackHost(uri.host))) {
        "GenericProvider requires an https:// baseUrl (got: $baseUrl). Plain http would let an on-path " +
            "attacker tamper with the update manifest, checksums and artifact together. http is permitted " +
            "only for loopback hosts (local testing)."
    }
}

private fun isLoopbackHost(host: String?): Boolean =
    host != null &&
        // URI.getHost() keeps the brackets of an IPv6 literal.
        (host.equals("localhost", ignoreCase = true) || host == "[::1]" || isIpv4LoopbackLiteral(host))

private const val IPV4_OCTETS = 4
private const val MAX_OCTET_DIGITS = 3
private const val MAX_OCTET = 255

/**
 * Whether [host] is a dotted-quad IPv4 literal in `127.0.0.0/8`. Checked syntactically and never
 * resolved: `127.updates.example.com` is a remote name that merely starts with `127.`.
 */
private fun isIpv4LoopbackLiteral(host: String): Boolean {
    val octets = host.split('.')
    return octets.size == IPV4_OCTETS &&
        octets[0] == "127" &&
        octets.all { octet ->
            octet.length in 1..MAX_OCTET_DIGITS && octet.all { it in '0'..'9' } && octet.toInt() <= MAX_OCTET
        }
}
