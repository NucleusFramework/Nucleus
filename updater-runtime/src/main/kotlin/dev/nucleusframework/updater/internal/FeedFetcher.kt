package dev.nucleusframework.updater.internal

import dev.nucleusframework.updater.exception.NetworkException
import dev.nucleusframework.updater.internal.delta.readAtMost
import java.io.File
import java.io.InputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Reads feed resources — manifests, artifacts, block maps, signatures — over HTTP(S) or, for a
 * [dev.nucleusframework.updater.provider.LocalFileProvider], from `file:` URLs.
 */
internal class FeedFetcher(
    private val httpClient: HttpClient,
    private val stallTimeout: Duration = STALL_TIMEOUT,
    private val authHeaders: () -> Map<String, String>,
) {
    /** Reads a whole text resource; anything but a success is a [NetworkException]. */
    fun readText(url: String): String = open(url).use { it.readBytes().toString(Charsets.UTF_8) }

    /**
     * Reads a whole resource, or `null` when it is absent, unreadable or larger than [maxBytes] — for
     * optional companions.
     */
    fun readBytesOrNull(
        url: String,
        maxBytes: Long = Long.MAX_VALUE,
    ): ByteArray? =
        runCatching { open(url).use { readAtMost(it, maxBytes) } }
            .getOrNull()
            ?.takeIf { it.isNotEmpty() }

    /**
     * Opens a resource for streaming; anything but a success is a [NetworkException]. A server that
     * sends no headers for [RESPONSE_TIMEOUT], or stops sending the body for the stall timeout, fails
     * the request with an [java.io.IOException] instead of hanging it.
     */
    fun open(url: String): InputStream {
        if (isLocal(url)) {
            val file = File(URI.create(url))
            if (!file.isFile) throw NetworkException("No such file in the update feed: $file")
            return file.inputStream()
        }
        val builder =
            HttpRequest
                .newBuilder()
                .uri(URI.create(url))
                .timeout(RESPONSE_TIMEOUT)
                .GET()
        authHeaders().forEach { (key, value) -> builder.header(key, value) }
        val response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream())
        if (response.statusCode() != HTTP_OK) {
            response.body().close()
            throw NetworkException("HTTP ${response.statusCode()} for $url")
        }
        return StallGuardedInputStream(response.body(), stallTimeout)
    }

    companion object {
        private const val HTTP_OK = 200

        /** Time allowed for a response's headers; it does not bound reading the body. */
        private val RESPONSE_TIMEOUT: Duration = Duration.ofSeconds(30)

        /** Time a body may go without sending any data before the read fails. */
        private val STALL_TIMEOUT: Duration = Duration.ofSeconds(30)

        fun isLocal(url: String): Boolean = url.startsWith("file:", ignoreCase = true)
    }
}
