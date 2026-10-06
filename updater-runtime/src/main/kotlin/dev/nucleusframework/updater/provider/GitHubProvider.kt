package dev.nucleusframework.updater.provider

import dev.nucleusframework.core.runtime.Platform
import dev.nucleusframework.updater.exception.NetworkException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/**
 * Fetches updates from the GitHub Releases of [owner]/[repo].
 *
 * @property owner User or organization that owns the repository.
 * @property repo Repository name.
 * @property token Optional token, for private repositories and a higher API rate limit.
 * @property tagPrefix Text that precedes the version in release tag names. With the default `"v"`,
 *   version `1.2.3` is downloaded from the release tagged `v1.2.3`. Use `""` for bare `1.2.3` tags,
 *   or a per-product prefix such as `"cli-v"` when several products share one repository;
 *   pre-release channel lookups then only consider tags that carry this prefix.
 */
public class GitHubProvider(
    public val owner: String,
    public val repo: String,
    public val token: String? = null,
    public val tagPrefix: String = DEFAULT_TAG_PREFIX,
) : UpdateProvider {
    /** Keeps the constructor signatures that existed before [tagPrefix], for already-compiled callers. */
    @Deprecated("Kept for binary compatibility.", level = DeprecationLevel.HIDDEN)
    public constructor(
        owner: String,
        repo: String,
        token: String? = null,
    ) : this(owner, repo, token, DEFAULT_TAG_PREFIX)

    /**
     * Base URL for the GitHub REST API. Exposed as `internal` so tests in this module
     * can redirect API traffic to a local server; not part of the public API.
     */
    internal var apiBaseUrl: String = "https://api.github.com"

    override fun getUpdateMetadataUrl(
        channel: String,
        platform: Platform,
    ): String {
        val fileName = metadataFileName(channel, platform)
        return "https://github.com/$owner/$repo/releases/latest/download/$fileName"
    }

    override fun resolveMetadataUrl(
        channel: String,
        platform: Platform,
        httpClient: HttpClient,
    ): String {
        val fileName = metadataFileName(channel, platform)
        if (channel.equals(LATEST_CHANNEL, ignoreCase = true)) {
            return "https://github.com/$owner/$repo/releases/latest/download/$fileName"
        }
        val tag = findLatestPrereleaseTag(channel, httpClient)
        return "https://github.com/$owner/$repo/releases/download/$tag/$fileName"
    }

    override fun getDownloadUrl(
        fileName: String,
        version: String,
    ): String = "https://github.com/$owner/$repo/releases/download/$tagPrefix$version/$fileName"

    override fun authHeaders(): Map<String, String> =
        if (token != null) {
            mapOf("Authorization" to "token $token")
        } else {
            emptyMap()
        }

    private fun metadataFileName(
        channel: String,
        platform: Platform,
    ): String {
        val suffix = platformSuffix(platform)
        return if (suffix.isEmpty()) "$channel.yml" else "$channel-$suffix.yml"
    }

    private fun findLatestPrereleaseTag(
        channel: String,
        httpClient: HttpClient,
    ): String {
        val builder =
            HttpRequest
                .newBuilder()
                .uri(URI.create("$apiBaseUrl/repos/$owner/$repo/releases?per_page=$PER_PAGE"))
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2026-03-10")
        if (token != null) builder.header("Authorization", "Bearer $token")
        val request = builder.GET().build()

        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        val status = response.statusCode()
        if (status != HTTP_OK) {
            val rateLimited =
                status == HTTP_FORBIDDEN &&
                    response.headers().firstValue("X-RateLimit-Remaining").orElse(null) == "0"
            val detail =
                if (rateLimited) {
                    "rate limit exceeded — configure a token to raise the limit"
                } else {
                    "HTTP $status"
                }
            throw NetworkException("GitHub API failed while listing releases for $owner/$repo: $detail")
        }

        val releases = json.decodeFromString<List<GitHubRelease>>(response.body())
        val match =
            releases.firstOrNull { release ->
                release.prerelease && tagMatchesChannel(release.tagName, channel)
            } ?: throw NoSuchElementException(
                "No release found for channel '$channel' within the most recent $PER_PAGE releases. " +
                    "Publish a fresh release on this channel.",
            )
        return match.tagName
    }

    private fun tagMatchesChannel(
        tag: String,
        channel: String,
    ): Boolean {
        if (!tag.startsWith(tagPrefix)) return false
        // Strip the prefix first: it may itself contain '-' (e.g. "cli-v1.2.0-beta.1").
        val suffix = tag.removePrefix(tagPrefix).substringAfter('-', missingDelimiterValue = "")
        return suffix.startsWith(channel, ignoreCase = true)
    }

    private fun platformSuffix(platform: Platform): String =
        when (platform) {
            Platform.Windows -> ""
            Platform.MacOS -> "mac"
            Platform.Linux -> "linux"
            Platform.Unknown -> ""
        }

    @Serializable
    internal data class GitHubRelease(
        @SerialName("tag_name") val tagName: String,
        val prerelease: Boolean,
    )

    private companion object {
        const val LATEST_CHANNEL = "latest"
        const val DEFAULT_TAG_PREFIX = "v"
        const val PER_PAGE = 100
        const val HTTP_OK = 200
        const val HTTP_FORBIDDEN = 403
        val json = Json { ignoreUnknownKeys = true }
    }
}
