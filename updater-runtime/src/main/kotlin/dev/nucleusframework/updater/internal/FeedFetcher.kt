package dev.nucleusframework.updater.internal

import dev.nucleusframework.updater.exception.NetworkException
import java.io.File
import java.io.InputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/**
 * Reads feed resources — manifests, artifacts, block maps, signatures — over HTTP(S) or, for a
 * [dev.nucleusframework.updater.provider.LocalFileProvider], from `file:` URLs.
 */
internal class FeedFetcher(
    private val httpClient: HttpClient,
    private val authHeaders: () -> Map<String, String>,
) {
    /** Reads a whole text resource; anything but a success is a [NetworkException]. */
    fun readText(url: String): String = open(url).use { it.readBytes().toString(Charsets.UTF_8) }

    /** Reads a whole resource, or `null` when it is absent or unreadable — for optional companions. */
    fun readBytesOrNull(url: String): ByteArray? =
        runCatching { open(url).use { it.readBytes() } }
            .getOrNull()
            ?.takeIf { it.isNotEmpty() }

    /** Opens a resource for streaming; anything but a success is a [NetworkException]. */
    fun open(url: String): InputStream {
        if (isLocal(url)) {
            val file = File(URI.create(url))
            if (!file.isFile) throw NetworkException("No such file in the update feed: $file")
            return file.inputStream()
        }
        val builder = HttpRequest.newBuilder().uri(URI.create(url)).GET()
        authHeaders().forEach { (key, value) -> builder.header(key, value) }
        val response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream())
        if (response.statusCode() != HTTP_OK) {
            response.body().close()
            throw NetworkException("HTTP ${response.statusCode()} for $url")
        }
        return response.body()
    }

    companion object {
        private const val HTTP_OK = 200

        fun isLocal(url: String): Boolean = url.startsWith("file:", ignoreCase = true)
    }
}
