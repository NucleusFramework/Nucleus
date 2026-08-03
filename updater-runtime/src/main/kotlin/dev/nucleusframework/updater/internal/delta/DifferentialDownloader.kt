package dev.nucleusframework.updater.internal.delta

import kotlinx.coroutines.delay
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.io.RandomAccessFile
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.util.Base64

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
 * Any inconsistency — a server that ignores `Range`, a short response, an old file that no longer
 * matches its block map, a final digest mismatch — raises [DeltaUnavailableException] so the caller
 * falls back to a full download. The digest is computed while writing, so a corrupt result is never
 * handed to the installer.
 */
internal class DifferentialDownloader(
    private val httpClient: HttpClient,
    private val authHeaders: Map<String, String> = emptyMap(),
) {
    /**
     * Writes [DeltaDownload.target] and returns the number of bytes actually transferred.
     * [onProgress] receives `(transferred, totalToTransfer)` — network bytes only, so the reported
     * progress reflects the download the user is waiting for rather than the size of the artifact.
     */
    suspend fun download(
        request: DeltaDownload,
        onProgress: suspend (Long, Long) -> Unit,
    ): Long {
        verifyPlanCoversArtifact(request)
        val digest = MessageDigest.getInstance(SHA_512)
        val transferred =
            try {
                assemble(request, digest, onProgress)
            } catch (e: IOException) {
                request.target.delete()
                throw DeltaUnavailableException("Differential download failed", e)
            }
        verifyAssembled(request, digest)
        return transferred
    }

    /** Fetches `[start, endInclusive]` of [url] into memory, for reading an artifact's own tail. */
    fun readRange(
        url: String,
        start: Long,
        endInclusive: Long,
    ): ByteArray {
        val response = httpClient.send(rangeRequest(url, start, endInclusive), HttpResponse.BodyHandlers.ofByteArray())
        if (response.statusCode() != HTTP_PARTIAL_CONTENT) {
            throw DeltaUnavailableException("Server answered HTTP ${response.statusCode()} for a range on $url")
        }
        val expected = endInclusive - start + 1
        if (response.body().size.toLong() != expected) {
            throw DeltaUnavailableException("Range response returned ${response.body().size} of $expected bytes")
        }
        return response.body()
    }

    private fun verifyPlanCoversArtifact(request: DeltaDownload) {
        val plannedSize = request.operations.sumOf { it.length } + (request.trailer?.size ?: 0)
        if (plannedSize != request.expectedSize) {
            throw DeltaUnavailableException(
                "Plan covers $plannedSize bytes but the manifest declares ${request.expectedSize}",
            )
        }
    }

    private fun verifyAssembled(
        request: DeltaDownload,
        digest: MessageDigest,
    ) {
        val actual = Base64.getEncoder().encodeToString(digest.digest())
        if (actual != request.expectedSha512 || request.target.length() != request.expectedSize) {
            request.target.delete()
            throw DeltaUnavailableException("Assembled artifact does not match the manifest")
        }
    }

    private suspend fun assemble(
        request: DeltaDownload,
        digest: MessageDigest,
        onProgress: suspend (Long, Long) -> Unit,
    ): Long =
        RandomAccessFile(request.oldFile, "r").use { old ->
            DigestOutputStream(request.target.outputStream().buffered(), digest).use { out ->
                runOperations(request, old, out, onProgress)
            }
        }

    private suspend fun runOperations(
        request: DeltaDownload,
        old: RandomAccessFile,
        out: OutputStream,
        onProgress: suspend (Long, Long) -> Unit,
    ): Long {
        val total = DeltaPlan.downloadSize(request.operations)
        var transferred = 0L
        var rangeRequests = 0
        for (operation in request.operations) {
            when (operation.kind) {
                OperationKind.COPY -> copyFromOldFile(old, operation, out)
                OperationKind.DOWNLOAD -> {
                    throttle(rangeRequests++)
                    downloadRange(request.url, operation, out) { chunk ->
                        transferred += chunk
                        onProgress(transferred, total)
                    }
                }
            }
        }
        request.trailer?.let(out::write)
        return transferred
    }

    /** Mirrors electron-updater: a brief pause so a long run of ranged requests avoids rate limits. */
    private suspend fun throttle(rangeRequests: Int) {
        if (rangeRequests > 0 && rangeRequests % RANGE_REQUEST_PAUSE_EVERY == 0) {
            delay(RANGE_REQUEST_PAUSE_MS)
        }
    }

    private fun copyFromOldFile(
        old: RandomAccessFile,
        operation: Operation,
        out: OutputStream,
    ) {
        if (operation.end > old.length()) {
            throw DeltaUnavailableException(
                "Cached artifact is shorter (${old.length()}) than its block map claims (${operation.end})",
            )
        }
        old.seek(operation.start)
        val buffer = ByteArray(BUFFER_SIZE)
        var remaining = operation.length
        while (remaining > 0) {
            val read = old.read(buffer, 0, minOf(remaining, buffer.size.toLong()).toInt())
            if (read <= 0) throw DeltaUnavailableException("Unexpected end of the cached artifact")
            out.write(buffer, 0, read)
            remaining -= read
        }
    }

    private suspend fun downloadRange(
        url: String,
        operation: Operation,
        out: OutputStream,
        onChunk: suspend (Int) -> Unit,
    ) {
        val request = rangeRequest(url, operation.start, operation.end - 1)
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream())
        if (response.statusCode() != HTTP_PARTIAL_CONTENT) {
            response.body().close()
            throw DeltaUnavailableException(
                "Server answered HTTP ${response.statusCode()} instead of $HTTP_PARTIAL_CONTENT: " +
                    "range requests are not supported",
            )
        }

        var remaining = operation.length
        val buffer = ByteArray(BUFFER_SIZE)
        response.body().use { input ->
            while (remaining > 0) {
                val read = input.read(buffer, 0, minOf(remaining, buffer.size.toLong()).toInt())
                if (read <= 0) throw DeltaUnavailableException("Range response ended $remaining bytes early")
                out.write(buffer, 0, read)
                remaining -= read
                onChunk(read)
            }
        }
    }

    private fun rangeRequest(
        url: String,
        start: Long,
        endInclusive: Long,
    ): HttpRequest {
        val builder =
            HttpRequest
                .newBuilder()
                .uri(URI.create(url))
                .header("Range", "bytes=$start-$endInclusive")
                .GET()
        authHeaders.forEach { (key, value) -> builder.header(key, value) }
        return builder.build()
    }

    private companion object {
        const val SHA_512 = "SHA-512"
        const val HTTP_PARTIAL_CONTENT = 206
        const val BUFFER_SIZE = 64 * 1024
        const val RANGE_REQUEST_PAUSE_EVERY = 100
        const val RANGE_REQUEST_PAUSE_MS = 1000L
    }
}
