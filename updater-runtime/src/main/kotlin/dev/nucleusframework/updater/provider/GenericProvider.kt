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
    host == "localhost" || host == "127.0.0.1" || host == "::1" || host?.startsWith("127.") == true
