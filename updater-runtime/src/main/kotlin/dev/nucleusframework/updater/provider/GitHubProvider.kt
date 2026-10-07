package dev.nucleusframework.updater.provider

import dev.nucleusframework.core.runtime.Platform
import dev.nucleusframework.updater.exception.NetworkException
import dev.nucleusframework.updater.provider.GitHubReleases.isOnChannel
import dev.nucleusframework.updater.provider.GitHubReleases.isStable
import dev.nucleusframework.updater.provider.GitHubReleases.metadataFileName
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/**
 * Updates from the releases of a public GitHub repository, read through GitHub's anonymous web
 * routes (no REST API, so no rate limit). Stable uses the `releases/latest` redirect; other
 * channels take the newest matching tag from `releases.atom`, as electron-updater does.
 *
 * Private and internal repositories, and Enterprise Servers in private mode, need
 * [PrivateGitHubProvider].
 *
 * @property host e.g. `github.example.com` for GitHub Enterprise Server; may include a port.
 * @property protocol `"https"`, or `"http"` for a loopback host (local testing).
 */
public class GitHubProvider(
    public val owner: String,
    public val repo: String,
    public val host: String = GitHubReleases.DEFAULT_HOST,
    public val protocol: String = GitHubReleases.DEFAULT_PROTOCOL,
) : UpdateProvider {
    private val baseUrl: String = GitHubReleases.baseUrl(protocol, host, "GitHubProvider")

    override fun getUpdateMetadataUrl(
        channel: String,
        platform: Platform,
    ): String = "$baseUrl/$owner/$repo/releases/latest/download/${metadataFileName(channel, platform)}"

    override fun resolveMetadataUrl(
        channel: String,
        platform: Platform,
        httpClient: HttpClient,
    ): String {
        val fileName = metadataFileName(channel, platform)
        if (isStable(channel)) {
            return "$baseUrl/$owner/$repo/releases/latest/download/$fileName"
        }
        val tag = findTagForChannel(channel, httpClient)
        return "$baseUrl/$owner/$repo/releases/download/$tag/$fileName"
    }

    override fun getDownloadUrl(
        fileName: String,
        version: String,
    ): String = "$baseUrl/$owner/$repo/releases/download/v$version/$fileName"

    /** The newest tag on [channel] in the releases feed. */
    private fun findTagForChannel(
        channel: String,
        httpClient: HttpClient,
    ): String {
        // Entries link to `.../releases/tag/<tag>`, newest first.
        val tags = TAG_HREF_REGEX.findAll(fetchFeed(httpClient)).map { it.groupValues[1] }.toList()
        if (tags.isEmpty()) {
            throw NoSuchElementException("No published versions for $owner/$repo on GitHub.")
        }
        return tags.firstOrNull { isOnChannel(it, channel) }
            ?: throw NoSuchElementException(
                "No release found for channel '$channel' in the GitHub releases feed for $owner/$repo. " +
                    "Publish a release on this channel.",
            )
    }

    /** Reads `/<owner>/<repo>/releases.atom`. */
    private fun fetchFeed(httpClient: HttpClient): String {
        val request =
            HttpRequest
                .newBuilder()
                .uri(URI.create("$baseUrl/$owner/$repo/releases.atom"))
                .header("Accept", "application/atom+xml, application/xml, text/xml, */*")
                .GET()
                .build()

        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() != HTTP_OK) {
            throw NetworkException("GitHub releases feed failed for $owner/$repo: HTTP ${response.statusCode()}")
        }
        return response.body()
    }
}

private const val HTTP_OK = 200
private val TAG_HREF_REGEX = Regex("""/releases/tag/([^"]+)""")
