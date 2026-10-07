package dev.nucleusframework.updater.provider

import dev.nucleusframework.core.runtime.Platform
import java.net.URI

/** Shared by [GitHubProvider] and [PrivateGitHubProvider]. */
internal object GitHubReleases {
    const val HTTPS = "https"
    private const val HTTP = "http"
    const val DEFAULT_HOST = "github.com"
    const val DEFAULT_PROTOCOL = HTTPS
    const val LATEST_CHANNEL = "latest"

    fun isStable(channel: String): Boolean = channel.equals(LATEST_CHANNEL, ignoreCase = true)

    /** `latest.yml` on Windows, `<channel>-mac.yml` / `<channel>-linux.yml` elsewhere. */
    fun metadataFileName(
        channel: String,
        platform: Platform,
    ): String {
        val suffix =
            when (platform) {
                Platform.Windows -> ""
                Platform.MacOS -> "mac"
                Platform.Linux -> "linux"
                Platform.Unknown -> ""
            }
        return if (suffix.isEmpty()) "$channel.yml" else "$channel-$suffix.yml"
    }

    /**
     * Whether [tag]'s first pre-release identifier is [channel], ignoring case (`v2.3.5-beta.8` →
     * `beta`; `v1.0.0-beta-leftover` is not). Same rule as electron-updater.
     */
    fun isOnChannel(
        tag: String,
        channel: String,
    ): Boolean =
        tag
            .substringAfter('-', missingDelimiterValue = "")
            .substringBefore('.')
            .equals(channel, ignoreCase = true)

    /**
     * Validates [protocol] and [host] and returns `<protocol>://<host>`. Plain http is accepted for
     * a loopback host only, as in [GenericProvider]: it would let an attacker swap the manifest and
     * the artifact together.
     */
    fun baseUrl(
        protocol: String,
        host: String,
        provider: String,
    ): String {
        val scheme = protocol.lowercase()
        require(scheme == HTTPS || scheme == HTTP) {
            "$provider protocol must be \"$HTTPS\" or \"$HTTP\" (got: $protocol)."
        }
        val uri = runCatching { URI("$scheme://$host") }.getOrNull()
        require(
            host.isNotBlank() &&
                uri?.host != null &&
                uri.rawPath.isNullOrEmpty() &&
                uri.rawQuery == null &&
                uri.rawFragment == null &&
                uri.rawUserInfo == null,
        ) {
            "$provider host must be a bare host name such as \"github.example.com\" " +
                "(optionally with a port), without a scheme or path (got: $host)."
        }
        require(scheme == HTTPS || isLoopbackHost(uri.host)) {
            "$provider allows http only for a loopback host (local testing), got: $host. Plain http would " +
                "let an on-path attacker tamper with the update manifest and the artifact together."
        }
        return "$scheme://$host"
    }
}
