package dev.nucleusframework.updater.provider

import dev.nucleusframework.core.runtime.Platform
import dev.nucleusframework.updater.exception.NetworkException
import dev.nucleusframework.updater.provider.GitHubReleases.isOnChannel
import dev.nucleusframework.updater.provider.GitHubReleases.isReleaseTag
import dev.nucleusframework.updater.provider.GitHubReleases.isStable
import dev.nucleusframework.updater.provider.GitHubReleases.metadataFileName
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.ConcurrentHashMap

/**
 * Updates from a GitHub repository that needs a token to read: private or internal repositories,
 * and Enterprise Servers in private mode. electron-updater's `PrivateGitHubProvider`.
 *
 * GitHub's web routes take no token, so everything goes through the REST API. Stable reads
 * `releases/latest`; other channels take the newest non-draft release on that channel among the
 * 100 most recent. Files are downloaded as release assets, which redirect to a signed storage URL;
 * the JDK `HttpClient` drops `Authorization` on redirect, so the token stays with GitHub.
 *
 * The token ships inside the app: give it read access to the repository's contents and nothing
 * more. Its REST quota (5,000 requests an hour) is shared by every installation. A check costs one
 * request; a download costs one per file (manifest, artifact, block map, signature) and one per
 * range request of a differential download, since each request to an asset goes through the API.
 * For a large install base, consider `differentialDownload = false`.
 *
 * @property host e.g. `github.example.com` for GitHub Enterprise Server (API at `/api/v3`); may
 *   include a port.
 * @property protocol `"https"`, or `"http"` for a loopback host (local testing).
 */
public class PrivateGitHubProvider(
    public val owner: String,
    public val repo: String,
    private val token: String,
    public val host: String = GitHubReleases.DEFAULT_HOST,
    public val protocol: String = GitHubReleases.DEFAULT_PROTOCOL,
) : UpdateProvider {
    init {
        require(token.isNotBlank()) { "PrivateGitHubProvider requires a token" }
    }

    internal val apiBaseUrl: String =
        GitHubReleases.baseUrl(protocol, host, "PrivateGitHubProvider").let { baseUrl ->
            if (host == GitHubReleases.DEFAULT_HOST) "${GitHubReleases.HTTPS}://api.github.com" else "$baseUrl/api/v3"
        }

    /** The release [resolveMetadataUrl] picked last. */
    @Volatile
    private var resolved: Release? = null

    /** Resolved releases by asset URL, to find an artifact's block map and signature. */
    private val releasesByAssetUrl = ConcurrentHashMap<String, Release>()

    /** Unsupported: the manifest URL comes from the API, through [resolveMetadataUrl]. */
    override fun getUpdateMetadataUrl(
        channel: String,
        platform: Platform,
    ): String =
        throw UnsupportedOperationException(
            "PrivateGitHubProvider locates its manifest through the GitHub API: call resolveMetadataUrl",
        )

    override fun resolveMetadataUrl(
        channel: String,
        platform: Platform,
        httpClient: HttpClient,
    ): String {
        val fileName = metadataFileName(channel, platform)
        val release = findRelease(channel, httpClient)
        val url =
            release.assets[fileName]
                ?: throw NoSuchElementException(
                    "Release ${release.tag} of $owner/$repo has no $fileName asset. " +
                        "Publish the update manifest with the release.",
                )
        release.assets.values.forEach { releasesByAssetUrl[it] = release }
        resolved = release
        return url
    }

    /** The API URL of asset [fileName] in the release [resolveMetadataUrl] picked. */
    override fun getDownloadUrl(
        fileName: String,
        version: String,
    ): String {
        val release = checkNotNull(resolved) { "resolveMetadataUrl must run before getDownloadUrl" }
        return release.assets[fileName]
            ?: throw NoSuchElementException(
                "Release ${release.tag} of $owner/$repo has no $fileName asset, though its update manifest lists it.",
            )
    }

    override fun getBlockMapUrl(fileUrl: String): String = companionAssetUrl(fileUrl, BLOCK_MAP_SUFFIX)

    override fun getSignatureUrl(fileUrl: String): String = companionAssetUrl(fileUrl, SIGNATURE_SUFFIX)

    /** Without `octet-stream` the API returns the asset's JSON instead of its bytes. */
    override fun authHeaders(): Map<String, String> =
        mapOf(
            "Authorization" to "Bearer $token",
            "Accept" to "application/octet-stream",
        )

    private fun companionAssetUrl(
        fileUrl: String,
        suffix: String,
    ): String {
        val release = releasesByAssetUrl[fileUrl]
        val name =
            release
                ?.assets
                ?.entries
                ?.firstOrNull { it.value == fileUrl }
                ?.key
        // No such asset: return a URL that 404s, which the updater reads as "not published".
        return name?.let { release.assets["$it$suffix"] } ?: "$fileUrl$suffix"
    }

    private fun findRelease(
        channel: String,
        httpClient: HttpClient,
    ): Release {
        val releasesUrl = "$apiBaseUrl/repos/$owner/$repo/releases"
        if (isStable(channel)) {
            return json.decodeFromString<ApiRelease>(getJson("$releasesUrl/latest", httpClient)).toRelease()
        }
        val releases = json.decodeFromString<List<ApiRelease>>(getJson("$releasesUrl?per_page=$PER_PAGE", httpClient))
        val match =
            releases.firstOrNull { !it.draft && isReleaseTag(it.tagName) && isOnChannel(it.tagName, channel) }
                ?: throw NoSuchElementException(
                    "No release found for channel '$channel' within the most recent $PER_PAGE releases of " +
                        "$owner/$repo. Publish a release on this channel.",
                )
        return match.toRelease()
    }

    private fun getJson(
        url: String,
        httpClient: HttpClient,
    ): String {
        val request =
            HttpRequest
                .newBuilder()
                .uri(URI.create(url))
                .header("Accept", "application/vnd.github+json")
                .header("Authorization", "Bearer $token")
                .GET()
                .build()
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        val status = response.statusCode()
        if (status == HTTP_OK) return response.body()

        val detail =
            when {
                status == HTTP_UNAUTHORIZED -> "the token was rejected (HTTP 401)"
                status == HTTP_FORBIDDEN &&
                    response.headers().firstValue("X-RateLimit-Remaining").orElse(null) == "0" ->
                    "API rate limit exceeded (HTTP 403)"
                status == HTTP_NOT_FOUND ->
                    "not found (HTTP 404) — the repository does not exist, the token cannot read it, " +
                        "or it has no published release"
                else -> "HTTP $status"
            }
        throw NetworkException("GitHub API request for $owner/$repo releases failed: $detail")
    }

    private class Release(
        val tag: String,
        /** Asset name → its API URL. */
        val assets: Map<String, String>,
    )

    @Serializable
    private class ApiRelease(
        @SerialName("tag_name") val tagName: String,
        val draft: Boolean = false,
        val assets: List<ApiAsset> = emptyList(),
    ) {
        fun toRelease(): Release = Release(tagName, assets.associate { it.name to it.url })
    }

    @Serializable
    private class ApiAsset(
        val name: String,
        val url: String,
    )
}

private const val PER_PAGE = 100
private const val HTTP_OK = 200
private const val HTTP_UNAUTHORIZED = 401
private const val HTTP_FORBIDDEN = 403
private const val HTTP_NOT_FOUND = 404
private const val BLOCK_MAP_SUFFIX = ".blockmap"
private const val SIGNATURE_SUFFIX = ".asc"
private val json = Json { ignoreUnknownKeys = true }
