package dev.nucleusframework.updater.internal.delta

import dev.nucleusframework.updater.internal.ChecksumVerifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.time.Duration

/** Everything needed to assemble one artifact from an older copy of it plus ranged requests. */
internal class DeltaDownload(
    val url: String,
    val oldFile: File,
    val target: File,
    val operations: List<Operation>,
    val expectedSize: Long,
    val expectedSha512: String,
    /**
     * Bytes to append verbatim after the last operation. Used for artifacts that carry their block
     * map in their own tail: that tail was already fetched to read the new block map, so it is
     * written from memory instead of being downloaded a second time.
     */
    val trailer: ByteArray? = null,
)

/**
 * Assembles a new artifact from an old one on disk plus HTTP range requests for the parts that
 * actually changed, then validates the result against the manifest SHA-512.
 *
 * Every operation's place in the new artifact is fixed by the plan, so operations are written by
 * position and need not run in order: copies from the old file run alongside up to
 * [MAX_CONCURRENT_RANGES] range requests, which an HTTP/2 client multiplexes over one connection.
 * Many small ranges are then bound by bandwidth rather than by one round trip each. Ranges are single
 * `bytes=a-b` requests; the multipart/byteranges form is not used, since GitHub and S3 do not serve it.
 *
 * A range that fails transiently (an I/O error, HTTP 429 or 5xx) is retried with backoff, honouring
 * `Retry-After`; an expired redirect target is re-resolved through the original URL. Any other
 * inconsistency — a server that ignores `Range`, a short response, an old file that no longer matches
 * its block map, a final digest mismatch — raises [DeltaUnavailableException] so the caller falls
 * back to a full download. The assembled file is checked against the manifest SHA-512 before it is
 * handed back, so a corrupt result never reaches the installer.
 */
internal class DifferentialDownloader(
    private val httpClient: HttpClient,
    private val authHeaders: Map<String, String> = emptyMap(),
) {
    /** An operation and its offset in the new artifact. */
    private class Placed(
        val operation: Operation,
        val position: Long,
    )

    /**
     * Where range requests go: the original URL until a response reveals where it redirects, then
     * that target, so later ranges skip the redirect round trip.
     */
    private class RangeSource(
        val originalUri: URI,
    ) {
        @Volatile
        var uri: URI = originalUri

        /** Re-resolutions left for expired redirect targets, shared by all workers. */
        @Volatile
        var reResolutionsLeft: Int = MAX_RE_RESOLUTIONS
    }

    /**
     * Writes [DeltaDownload.target] and returns the number of bytes actually transferred.
     * [onProgress] receives `(transferred, totalToTransfer)` — network bytes only, so the reported
     * progress reflects the download the user is waiting for rather than the size of the artifact.
     * It is always called from the caller's coroutine, never from a worker, so it may emit into a Flow.
     */
    suspend fun download(
        request: DeltaDownload,
        onProgress: suspend (Long, Long) -> Unit,
    ): Long {
        verifyPlanCoversArtifact(request)
        val transferred =
            try {
                assemble(request, onProgress)
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Exception,
            ) {
                // Never leave a partly assembled file behind, whatever interrupted the assembly.
                request.target.delete()
                throw if (e is IOException) DeltaUnavailableException("Differential download failed", e) else e
            }
        verifyAssembled(request)
        return transferred
    }

    /**
     * Fetches `[start, endInclusive]` of [url] into memory, for reading an artifact's own tail.
     * Reads at most the requested length, however much the server sends.
     */
    fun readRange(
        url: String,
        start: Long,
        endInclusive: Long,
    ): ByteArray {
        val request = rangeRequest(URI.create(url), authHeaders, start, endInclusive)
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream())
        if (response.statusCode() != HTTP_PARTIAL_CONTENT) {
            response.body().close()
            throw DeltaUnavailableException("Server answered HTTP ${response.statusCode()} for a range on $url")
        }
        val expected = endInclusive - start + 1
        val body = response.body().use { readAtMost(it, expected) }
        if (body.size.toLong() != expected) {
            throw DeltaUnavailableException("Range response returned ${body.size} of $expected bytes")
        }
        return body
    }

    private fun verifyPlanCoversArtifact(request: DeltaDownload) {
        val plannedSize = request.operations.sumOf { it.length } + (request.trailer?.size ?: 0)
        if (plannedSize != request.expectedSize) {
            throw DeltaUnavailableException(
                "Plan covers $plannedSize bytes but the manifest declares ${request.expectedSize}",
            )
        }
    }

    private fun verifyAssembled(request: DeltaDownload) {
        val target = request.target
        if (target.length() != request.expectedSize || !ChecksumVerifier.verify(target, request.expectedSha512)) {
            target.delete()
            throw DeltaUnavailableException("Assembled artifact does not match the manifest")
        }
    }

    private suspend fun assemble(
        request: DeltaDownload,
        onProgress: suspend (Long, Long) -> Unit,
    ): Long {
        val copies = mutableListOf<Placed>()
        val downloads = mutableListOf<Placed>()
        var position = 0L
        for (operation in request.operations) {
            val placed = Placed(operation, position)
            when (operation.kind) {
                OperationKind.COPY -> copies += placed
                OperationKind.DOWNLOAD -> downloads += placed
            }
            position += operation.length
        }
        val total = DeltaPlan.downloadSize(request.operations)
        var transferred = 0L

        RandomAccessFile(request.target, "rw").use { target ->
            target.setLength(0)
            target.setLength(request.expectedSize)
            val output = target.channel

            coroutineScope {
                val progress = Channel<Long>(Channel.UNLIMITED)
                launch(Dispatchers.IO) { copyFromOldFile(request.oldFile, copies, output) }
                launch(Dispatchers.IO) {
                    try {
                        downloadRanges(RangeSource(URI.create(request.url)), downloads, output, progress)
                    } finally {
                        progress.close()
                    }
                }
                for (bytes in progress) {
                    transferred += bytes
                    onProgress(transferred, total)
                }
            }

            request.trailer?.let { writeFully(output, it, it.size, position) }
        }
        return transferred
    }

    private fun copyFromOldFile(
        oldFile: File,
        copies: List<Placed>,
        output: FileChannel,
    ) {
        if (copies.isEmpty()) return
        RandomAccessFile(oldFile, "r").use { old ->
            val buffer = ByteArray(BUFFER_SIZE)
            for (placed in copies) {
                val operation = placed.operation
                if (operation.end > old.length()) {
                    throw DeltaUnavailableException(
                        "Cached artifact is shorter (${old.length()}) than its block map claims (${operation.end})",
                    )
                }
                old.seek(operation.start)
                var copied = 0L
                while (copied < operation.length) {
                    val read = old.read(buffer, 0, minOf(operation.length - copied, buffer.size.toLong()).toInt())
                    if (read <= 0) throw DeltaUnavailableException("Unexpected end of the cached artifact")
                    writeFully(output, buffer, read, placed.position + copied)
                    copied += read
                }
            }
        }
    }

    private suspend fun downloadRanges(
        source: RangeSource,
        downloads: List<Placed>,
        output: FileChannel,
        progress: SendChannel<Long>,
    ) {
        if (downloads.isEmpty()) return
        // The first range runs alone so it resolves any redirect once; the rest then go straight to
        // the resolved URI instead of each negotiating the redirect.
        downloadRange(source, downloads.first(), output, progress)
        val queue = Channel<Placed>(Channel.UNLIMITED)
        downloads.drop(1).forEach { queue.trySend(it) }
        queue.close()
        coroutineScope {
            repeat(minOf(MAX_CONCURRENT_RANGES, downloads.size - 1)) {
                launch { for (placed in queue) downloadRange(source, placed, output, progress) }
            }
        }
    }

    /** Outcome of one attempt at a range that did not complete. */
    private class Retry(
        val reason: String,
        val retryAfterMs: Long?,
    )

    /** Downloads one range into place, retrying transient failures. */
    private suspend fun downloadRange(
        source: RangeSource,
        placed: Placed,
        output: FileChannel,
        progress: SendChannel<Long>,
    ) {
        // Bytes of this range already reported: a retry rewrites the range from its start, so only
        // bytes beyond this are reported again and progress never moves backwards.
        var reported = 0L
        var attempt = 1
        while (true) {
            val retry =
                (
                    try {
                        attemptRange(source, placed, output) { received ->
                            if (received > reported) {
                                progress.send(received - reported)
                                reported = received
                            }
                        }
                    } catch (e: IOException) {
                        Retry(e.message ?: e.javaClass.simpleName, retryAfterMs = null)
                    }
                ) ?: return
            if (attempt >= MAX_ATTEMPTS) {
                throw DeltaUnavailableException(
                    "${describe(placed.operation)} failed after $attempt attempts: ${retry.reason}",
                )
            }
            delay(retry.retryAfterMs ?: (BASE_RETRY_DELAY_MS shl (attempt - 1)))
            attempt++
        }
    }

    /** One attempt at a range; returns `null` once it is written, or why it should be retried. */
    private suspend fun attemptRange(
        source: RangeSource,
        placed: Placed,
        output: FileChannel,
        onReceived: suspend (Long) -> Unit,
    ): Retry? {
        val operation = placed.operation
        val uri = source.uri
        // Credentials are meant for the original host: forwarding e.g. a GitHub token to the
        // pre-signed CDN URL a redirect resolved to gets the request rejected.
        val headers = if (uri.host == source.originalUri.host) authHeaders else emptyMap()
        val response =
            httpClient.send(
                rangeRequest(uri, headers, operation.start, operation.end - 1),
                HttpResponse.BodyHandlers.ofInputStream(),
            )
        if (response.statusCode() != HTTP_PARTIAL_CONTENT) {
            response.body().close()
            return retryFor(source, uri, response, operation)
        }

        var received = 0L
        response.body().use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read == -1) break
                if (received + read > operation.length) {
                    throw DeltaUnavailableException(
                        "${describe(operation)} returned more than ${operation.length} bytes",
                    )
                }
                writeFully(output, buffer, read, placed.position + received)
                received += read
                onReceived(received)
            }
        }
        if (received != operation.length) {
            throw IOException("${describe(operation)} ended after $received of ${operation.length} bytes")
        }
        source.uri = response.uri()
        return null
    }

    /** Classifies a response other than 206: a retry for transient failures, otherwise a failed delta. */
    private fun retryFor(
        source: RangeSource,
        uri: URI,
        response: HttpResponse<*>,
        operation: Operation,
    ): Retry {
        val status = response.statusCode()
        return when {
            status == HTTP_OK ->
                throw DeltaUnavailableException(
                    "Server answered HTTP $HTTP_OK instead of $HTTP_PARTIAL_CONTENT: range requests are not supported",
                )

            // A redirect target that starts refusing is most likely an expired pre-signed CDN URL:
            // go back through the original URL for a fresh one.
            (status == HTTP_UNAUTHORIZED || status == HTTP_FORBIDDEN) &&
                uri != source.originalUri &&
                source.reResolutionsLeft > 0 -> {
                synchronized(source) {
                    if (source.uri == uri) {
                        source.reResolutionsLeft--
                        source.uri = source.originalUri
                    }
                }
                Retry("HTTP $status from $uri", retryAfterMs = 0)
            }

            status == HTTP_TOO_MANY_REQUESTS || status >= HTTP_SERVER_ERROR ->
                Retry("HTTP $status from $uri", retryAfterMs(response))

            else -> throw DeltaUnavailableException("HTTP $status downloading ${describe(operation)} from $uri")
        }
    }

    /** `Retry-After` in seconds, capped; `null` when absent or an HTTP date, so backoff applies. */
    private fun retryAfterMs(response: HttpResponse<*>): Long? =
        response
            .headers()
            .firstValue("Retry-After")
            .orElse(null)
            ?.trim()
            ?.toLongOrNull()
            ?.coerceIn(0, MAX_RETRY_AFTER_SECONDS)
            ?.times(MILLIS_PER_SECOND)

    /** Positional write of `bytes[0, length)`; positional writes are safe from concurrent threads. */
    private fun writeFully(
        output: FileChannel,
        bytes: ByteArray,
        length: Int,
        position: Long,
    ) {
        val buffer = ByteBuffer.wrap(bytes, 0, length)
        var offset = position
        while (buffer.hasRemaining()) {
            offset += output.write(buffer, offset)
        }
    }

    private fun describe(operation: Operation): String = "Range ${operation.start}-${operation.end - 1}"

    private fun rangeRequest(
        uri: URI,
        headers: Map<String, String>,
        start: Long,
        endInclusive: Long,
    ): HttpRequest {
        val builder =
            HttpRequest
                .newBuilder()
                .uri(uri)
                .timeout(RESPONSE_TIMEOUT)
                .header("Range", "bytes=$start-$endInclusive")
                .GET()
        headers.forEach { (key, value) -> builder.header(key, value) }
        return builder.build()
    }

    private companion object {
        const val HTTP_OK = 200
        const val HTTP_PARTIAL_CONTENT = 206
        const val HTTP_UNAUTHORIZED = 401
        const val HTTP_FORBIDDEN = 403
        const val HTTP_TOO_MANY_REQUESTS = 429
        const val HTTP_SERVER_ERROR = 500
        const val BUFFER_SIZE = 64 * 1024
        const val MAX_CONCURRENT_RANGES = 6
        const val MAX_ATTEMPTS = 3
        const val BASE_RETRY_DELAY_MS = 500L
        const val MAX_RETRY_AFTER_SECONDS = 10L
        const val MILLIS_PER_SECOND = 1000L
        const val MAX_RE_RESOLUTIONS = 3

        /**
         * Time allowed for a range response's headers to arrive. It does not bound reading the body,
         * so large ranges are unaffected; it stops a server that accepts the connection but never
         * answers from hanging the update.
         */
        val RESPONSE_TIMEOUT: Duration = Duration.ofSeconds(30)
    }
}
