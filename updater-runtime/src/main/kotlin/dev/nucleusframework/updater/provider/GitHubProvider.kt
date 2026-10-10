package dev.nucleusframework.updater.provider

import dev.nucleusframework.core.runtime.Platform
import dev.nucleusframework.updater.exception.NetworkException
import dev.nucleusframework.updater.provider.GitHubReleaseChannels.clientChannel
import dev.nucleusframework.updater.provider.GitHubReleaseChannels.latestFileName
import dev.nucleusframework.updater.provider.GitHubReleaseChannels.manifestFileName
import dev.nucleusframework.updater.provider.GitHubReleaseChannels.selectTag
import dev.nucleusframework.updater.provider.GitHubReleases.FEED_RELEASES
import dev.nucleusframework.updater.provider.GitHubReleases.assetName
import dev.nucleusframework.updater.provider.GitHubReleases.feedTags
import dev.nucleusframework.updater.provider.GitHubReleases.isReleaseTag
import dev.nucleusframework.updater.provider.GitHubReleases.isStable
import dev.nucleusframework.updater.provider.GitHubReleases.metadataFileName
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import javax.xml.stream.XMLStreamException

/**
 * Updates from the releases of a public GitHub repository, read through GitHub's anonymous web
 * routes (no REST API on github.com, so no rate limit), as electron-updater does.
 *
 * Without pre-releases the release is the repository's latest one. With them, it is picked from
 * `releases.atom` ([GitHubReleaseChannels.selectTag]), and its manifest is its own channel's
 * (`beta.yml` for `v2.0.0-beta.1`), else `latest*.yml`: a client on `beta` moves on to the release
 * that follows. Every file is then downloaded from that release's tag.
 *
 * The feed lists only the 10 most recent releases: a channel with none among them is not found
 * (stable is unaffected). Tags that are not SemVer versions are on no channel.
 *
 * Private and internal repositories, and Enterprise Servers in private mode, need
 * [PrivateGitHubProvider].
 *
 * @property host e.g. `github.example.com` for GitHub Enterprise Server, whose latest release is read
 *   from its REST API (`/api/v3`); may include a port.
 * @property protocol `"https"`, or `"http"` for a loopback host (local testing).
 */
public class GitHubProvider(
    public val owner: String,
    public val repo: String,
    public val host: String = GitHubReleases.DEFAULT_HOST,
    public val protocol: String = GitHubReleases.DEFAULT_PROTOCOL,
) : UpdateProvider {
    /**
     * Kept so that a call passing a token, `GitHubProvider(owner, repo, token)`, fails to compile
     * instead of taking the token as [host]. Any three positional arguments resolve here, so a host
     * is passed by name: `GitHubProvider(owner, repo, host = "github.example.com")`.
     */
    @Deprecated(
        "GitHubProvider reads public repositories only and takes no token. Use PrivateGitHubProvider.",
        ReplaceWith("PrivateGitHubProvider(owner, repo, token)"),
        DeprecationLevel.ERROR,
    )
    @Suppress("UNUSED_PARAMETER")
    public constructor(owner: String, repo: String, token: String) : this(owner, repo)

    private val baseUrl: String = GitHubReleases.baseUrl(protocol, host, "GitHubProvider")
    private val releasesUrl = "$baseUrl/$owner/$repo/releases"
    private val downloadUrl = "$releasesUrl/download/"

    override fun getUpdateMetadataUrl(
        channel: String,
        platform: Platform,
    ): String = "$releasesUrl/latest/download/${metadataFileName(channel, platform)}"

    /** As for a client that accepts pre-releases only on a pre-release channel. */
    override fun resolveMetadataUrl(
        channel: String,
        platform: Platform,
        httpClient: HttpClient,
    ): String = resolve(channel, platform, httpClient, currentVersion = null, allowPrerelease = !isStable(channel))

    override fun resolveMetadataUrl(
        channel: String,
        platform: Platform,
        httpClient: HttpClient,
        currentVersion: String,
        allowPrerelease: Boolean,
    ): String = resolve(channel, platform, httpClient, currentVersion, allowPrerelease)

    /** [fileName] under the tag `v<version>`; the manifest's own tag is only known from its URL. */
    override fun getDownloadUrl(
        fileName: String,
        version: String,
    ): String = "${downloadUrl}v$version/${assetName(fileName)}"

    /** [fileName] under the tag [metadataUrl] was read from. */
    override fun getDownloadUrl(
        fileName: String,
        version: String,
        metadataUrl: String,
    ): String {
        if (!metadataUrl.startsWith(downloadUrl)) return getDownloadUrl(fileName, version)
        val tag = metadataUrl.removePrefix(downloadUrl).substringBeforeLast('/')
        return "$downloadUrl$tag/${assetName(fileName)}"
    }

    private fun resolve(
        channel: String,
        platform: Platform,
        httpClient: HttpClient,
        currentVersion: String?,
        allowPrerelease: Boolean,
    ): String {
        val tag =
            if (allowPrerelease) {
                findFeedTag(
                    clientChannel(channel, currentVersion),
                    httpClient,
                )
            } else {
                findLatestTag(httpClient)
            }
        val url = "$downloadUrl$tag/${manifestFileName(tag, channel, platform)}"
        val latestUrl = "$downloadUrl$tag/${latestFileName(platform)}"
        // A release publishes no pre-release manifest: a pre-release client reads its latest*.yml.
        return if (!allowPrerelease || url == latestUrl || isPublished(url, httpClient)) url else latestUrl
    }

    /** The tag the client on [clientChannel] takes from the releases feed ([selectTag]). */
    private fun findFeedTag(
        clientChannel: String?,
        httpClient: HttpClient,
    ): String {
        val tags = readFeedTags(httpClient).filter(::isReleaseTag)
        if (tags.isEmpty()) {
            throw NoSuchElementException("No published versions for $owner/$repo on GitHub.")
        }
        return selectTag(tags, clientChannel)
            ?: throw NoSuchElementException(
                "No release found for channel '$clientChannel' among the $FEED_RELEASES most recent releases " +
                    "of $owner/$repo (the GitHub releases feed lists no more). Publish a release on this channel.",
            )
    }

    /**
     * The tag of the latest release. On github.com, the web route `releases/latest` redirects to the
     * release's page, which answers JSON when asked to; elsewhere, the REST API.
     */
    private fun findLatestTag(httpClient: HttpClient): String {
        val url =
            if (host.equals(GitHubReleases.DEFAULT_HOST, ignoreCase = true)) {
                "$releasesUrl/latest"
            } else {
                "$baseUrl/api/v3/repos/$owner/$repo/releases/latest"
            }
        val response = get(url, "application/json", httpClient)
        when (response.statusCode()) {
            HTTP_OK -> Unit
            HTTP_NOT_FOUND -> throw NoSuchElementException("No published release for $owner/$repo on GitHub.")
            else -> throw NetworkException(
                "GitHub latest release lookup failed for $owner/$repo: HTTP ${response.statusCode()}",
            )
        }
        return releaseTag(response.body())
    }

    private fun releaseTag(json: String): String =
        try {
            GitHubReleases.json.decodeFromString<GitHubReleases.TaggedRelease>(json).tagName
        } catch (e: IllegalArgumentException) {
            // SerializationException included.
            throw NetworkException("GitHub latest release of $owner/$repo did not answer its tag", e)
        }

    private fun readFeedTags(httpClient: HttpClient): List<String> =
        try {
            feedTags(fetchFeed(httpClient))
        } catch (e: XMLStreamException) {
            throw NetworkException("GitHub releases feed for $owner/$repo is not valid Atom", e)
        }

    /** Reads `/<owner>/<repo>/releases.atom`. */
    private fun fetchFeed(httpClient: HttpClient): String {
        val response = get("$baseUrl/$owner/$repo/releases.atom", ATOM_ACCEPT, httpClient)
        if (response.statusCode() != HTTP_OK) {
            throw NetworkException("GitHub releases feed failed for $owner/$repo: HTTP ${response.statusCode()}")
        }
        return response.body()
    }

    /** Whether [url] answers, probed with a `HEAD` that follows GitHub's redirect to storage. */
    private fun isPublished(
        url: String,
        httpClient: HttpClient,
    ): Boolean {
        val request =
            HttpRequest
                .newBuilder()
                .uri(URI.create(url))
                .method("HEAD", HttpRequest.BodyPublishers.noBody())
                .build()
        return httpClient.send(request, HttpResponse.BodyHandlers.discarding()).statusCode() == HTTP_OK
    }

    private fun get(
        url: String,
        accept: String,
        httpClient: HttpClient,
    ): HttpResponse<String> {
        val request =
            HttpRequest
                .newBuilder()
                .uri(URI.create(url))
                .header("Accept", accept)
                .GET()
                .build()
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString())
    }
}

private const val HTTP_OK = 200
private const val HTTP_NOT_FOUND = 404
private const val ATOM_ACCEPT = "application/atom+xml, application/xml, text/xml, */*"
