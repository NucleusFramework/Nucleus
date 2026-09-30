package dev.nucleusframework.updater.testing

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import dev.nucleusframework.core.runtime.Platform
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.io.RandomAccessFile
import java.net.InetAddress
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * A loopback HTTP server that serves an update feed the way a release host does — manifests,
 * artifacts, block maps and detached signatures, with byte ranges for differential downloads —
 * and can misbehave on demand ([fault]), to test an app's update flow end to end without
 * publishing a release.
 *
 * ```kotlin
 * UpdateFeedServer().use { feed ->
 *     feed.publish("2.0.0", File("build/compose/binaries/main/nsis/MyApp-2.0.0.exe"))
 *     feed.fault(FeedFault.Throttle(bytesPerSecond = 2_000_000))
 *
 *     val updater = NucleusUpdater {
 *         currentVersion = "1.0.0"
 *         executableType = "nsis"
 *         provider = GenericProvider(feed.baseUrl)
 *     }
 *     // checkForUpdates(), downloadUpdate(), …
 * }
 * ```
 *
 * An installed app is pointed at a running server with `NUCLEUS_UPDATER_FEED_URL=<baseUrl>` (see
 * `UpdaterConfig.allowLaunchOverrides`). The server only listens on the loopback interface.
 *
 * @param directory the feed root: files are served from it by name, and [publish] writes into it.
 *   Defaults to a fresh temporary directory, deleted by [close].
 * @param port the port to listen on; `0` picks a free one.
 */
public class UpdateFeedServer(
    directory: File? = null,
    port: Int = 0,
) : AutoCloseable {
    private val ownsDirectory = directory == null

    /** The feed root. */
    public val directory: File =
        (
            directory ?: kotlin.io.path
                .createTempDirectory("nucleus-update-feed-")
                .toFile()
        ).absoluteFile.normalize()

    private val executor: ExecutorService =
        Executors.newCachedThreadPool { runnable ->
            Thread(runnable, "nucleus-update-feed-${THREAD_IDS.incrementAndGet()}").apply { isDaemon = true }
        }
    private val server: HttpServer =
        HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0).apply {
            executor = this@UpdateFeedServer.executor
            createContext("/") { exchange -> serve(exchange) }
            start()
        }

    private val faults = CopyOnWriteArrayList<ActiveFault>()
    private val recorded = CopyOnWriteArrayList<FeedRequest>()

    /** The feed URL to hand to `GenericProvider` or `NUCLEUS_UPDATER_FEED_URL`. */
    public val baseUrl: String = "http://127.0.0.1:${server.address.port}"

    /** Every request answered so far, oldest first. */
    public val requests: List<FeedRequest> get() = recorded.toList()

    init {
        this.directory.mkdirs()
    }

    /**
     * Publishes [artifacts] as release [version]: copies them into [directory] with their `.blockmap`
     * and `.asc` companions when present next to them, and writes the `<channel>[-mac|-linux].yml`
     * manifest listing them with their SHA-512 and size, as electron-builder does. Publishing again
     * replaces the manifest, so a test can move the feed on to a newer version.
     *
     * @return the manifest file written.
     */
    public fun publish(
        version: String,
        artifacts: List<File>,
        channel: String = "latest",
        platform: Platform = Platform.Current,
        releaseDate: Instant = Instant.now(),
    ): File {
        require(artifacts.isNotEmpty()) { "Publish at least one artifact" }
        val entries =
            artifacts.map { artifact ->
                require(artifact.isFile) { "No such artifact: $artifact" }
                val published = copyIntoFeed(artifact)
                for (companion in COMPANION_EXTENSIONS) {
                    File(artifact.path + companion).takeIf { it.isFile }?.let(::copyIntoFeed)
                }
                ManifestEntry(published.name, sha512Base64(published), published.length())
            }
        val manifest = File(directory, manifestName(channel, platform))
        manifest.writeText(manifestYaml(version, entries, releaseDate))
        return manifest
    }

    /** [publish] for a vararg list of artifacts on the `latest` channel of this OS. */
    public fun publish(
        version: String,
        vararg artifacts: File,
    ): File = publish(version, artifacts.toList())

    /**
     * Injects [fault] into the responses for files whose name matches [path] (a glob: `*` matches
     * any run of characters, so `*.exe` or `latest*.yml`), for the next [times] matching requests.
     */
    public fun fault(
        fault: FeedFault,
        path: String = "*",
        times: Int = Int.MAX_VALUE,
    ) {
        require(times > 0) { "times must be positive, got $times" }
        faults += ActiveFault(fault, globToRegex(path), AtomicInteger(times))
    }

    /** Removes every injected fault. */
    public fun clearFaults() {
        faults.clear()
    }

    /** Forgets the [requests] recorded so far. */
    public fun clearRequests() {
        recorded.clear()
    }

    override fun close() {
        server.stop(0)
        executor.shutdownNow()
        executor.awaitTermination(STOP_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        if (ownsDirectory) directory.deleteRecursively()
    }

    private fun copyIntoFeed(file: File): File {
        val target = File(directory, file.name)
        if (file.absoluteFile.normalize() != target) file.copyTo(target, overwrite = true)
        return target
    }

    private fun serve(exchange: HttpExchange) {
        val method = exchange.requestMethod.uppercase()
        val path = exchange.requestURI.path.trimStart('/')
        val range = exchange.requestHeaders.getFirst("Range")
        var status = HTTP_NOT_FOUND
        val sent = longArrayOf(0)
        try {
            val applicable = takeFaults(path)
            applicable.firstNotNullOfOrNull { it as? FeedFault.Status }?.let { fault ->
                status = fault.code
                exchange.sendResponseHeaders(fault.code, -1)
                return
            }
            applicable.filterIsInstance<FeedFault.Delay>().forEach { Thread.sleep(it.duration.inWholeMilliseconds) }

            val file = File(directory, path).normalize()
            if (method !in SUPPORTED_METHODS) {
                status = HTTP_BAD_METHOD
                exchange.sendResponseHeaders(status, -1)
                return
            }
            if (!file.toPath().startsWith(directory.toPath()) || !file.isFile) {
                exchange.sendResponseHeaders(status, -1)
                return
            }

            val length = file.length()
            val requested = range?.takeUnless { FeedFault.IgnoreRange in applicable }?.let { parseRange(it, length) }
            if (requested == UNSATISFIABLE) {
                status = HTTP_RANGE_NOT_SATISFIABLE
                exchange.responseHeaders.add("Content-Range", "bytes */$length")
                exchange.sendResponseHeaders(status, -1)
                return
            }
            val (start, endInclusive) = requested ?: (0L to length - 1)
            val count = endInclusive - start + 1
            status = if (requested != null) HTTP_PARTIAL_CONTENT else HTTP_OK
            exchange.responseHeaders.add("Accept-Ranges", "bytes")
            if (requested != null) exchange.responseHeaders.add("Content-Range", "bytes $start-$endInclusive/$length")

            if (method == "HEAD") {
                exchange.responseHeaders.add("Content-Length", count.toString())
                exchange.sendResponseHeaders(status, -1)
                return
            }
            exchange.sendResponseHeaders(status, if (count == 0L) -1 else count)
            if (count > 0) streamBody(file, start, count, applicable, exchange.responseBody, sent)
        } catch (_: IOException) {
            // The client went away, or a Truncate fault dropped the connection on purpose.
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } finally {
            recorded += FeedRequest(method, path, range, status, sent[0])
            runCatching { exchange.close() }
        }
    }

    /** Sends [count] bytes of [file] from [start], applying the body faults and counting into [sentBytes]. */
    @Suppress("LongParameterList")
    private fun streamBody(
        file: File,
        start: Long,
        count: Long,
        applicable: List<FeedFault>,
        out: OutputStream,
        sentBytes: LongArray,
    ) {
        val limit = applicable.filterIsInstance<FeedFault.Truncate>().minOfOrNull { it.afterBytes } ?: Long.MAX_VALUE
        val rate = applicable.filterIsInstance<FeedFault.Throttle>().minOfOrNull { it.bytesPerSecond }
        val corruptAt = applicable.filterIsInstance<FeedFault.Corrupt>().map { it.offset }.toSet()
        val chunkSize =
            rate?.let { (it / THROTTLE_TICKS_PER_SECOND).coerceIn(1, BUFFER_SIZE.toLong()).toInt() } ?: BUFFER_SIZE
        val buffer = ByteArray(chunkSize)
        val began = System.nanoTime()
        var sent = 0L
        RandomAccessFile(file, "r").use { input ->
            input.seek(start)
            while (sent < count) {
                if (sent >= limit) {
                    // Put what was "sent" on the wire first: dropping it with the connection would look
                    // like a stale pooled connection, which HTTP clients silently retry.
                    out.flush()
                    throw IOException("Truncated by FeedFault.Truncate after $sent bytes")
                }
                val toRead = minOf(chunkSize.toLong(), count - sent, limit - sent).toInt()
                val read = input.read(buffer, 0, toRead)
                if (read < 0) break
                corruptAt
                    .map { it - (start + sent) }
                    .filter { it in 0 until read }
                    .forEach { index -> buffer[index.toInt()] = (buffer[index.toInt()].toInt() xor ALL_BITS).toByte() }
                out.write(buffer, 0, read)
                sent += read
                sentBytes[0] = sent
                if (rate != null) {
                    val dueNanos = sent * NANOS_PER_SECOND / rate
                    val aheadMillis = (dueNanos - (System.nanoTime() - began)) / NANOS_PER_MILLI
                    if (aheadMillis > 0) Thread.sleep(aheadMillis)
                }
            }
            out.flush()
        }
        if (sent < count) throw IOException("Truncated by FeedFault.Truncate after $sent bytes")
    }

    private fun takeFaults(path: String): List<FeedFault> =
        faults
            .filter { active ->
                active.pattern.matches(path) && active.remaining.getAndUpdate { if (it > 0) it - 1 else 0 } > 0
            }.map { it.fault }

    private class ActiveFault(
        val fault: FeedFault,
        val pattern: Regex,
        val remaining: AtomicInteger,
    )

    private class ManifestEntry(
        val url: String,
        val sha512: String,
        val size: Long,
    )

    /** Feed naming shared with the updater. */
    public companion object {
        private const val HTTP_OK = 200
        private const val HTTP_PARTIAL_CONTENT = 206
        private const val HTTP_NOT_FOUND = 404
        private const val HTTP_BAD_METHOD = 405
        private const val HTTP_RANGE_NOT_SATISFIABLE = 416
        private const val BUFFER_SIZE = 64 * 1024
        private const val ALL_BITS = 0xFF
        private const val THROTTLE_TICKS_PER_SECOND = 20
        private const val NANOS_PER_SECOND = 1_000_000_000L
        private const val NANOS_PER_MILLI = 1_000_000L
        private const val STOP_TIMEOUT_SECONDS = 5L
        private val SUPPORTED_METHODS = setOf("GET", "HEAD")
        private val COMPANION_EXTENSIONS = listOf(".blockmap", ".asc")
        private val UNSATISFIABLE = -1L to -1L
        private val THREAD_IDS = AtomicInteger()

        /** The manifest a client of [platform] reads for [channel]: `latest.yml`, `beta-mac.yml`, … */
        public fun manifestName(
            channel: String,
            platform: Platform = Platform.Current,
        ): String =
            when (platform) {
                Platform.MacOS -> "$channel-mac.yml"
                Platform.Linux -> "$channel-linux.yml"
                Platform.Windows, Platform.Unknown -> "$channel.yml"
            }

        private fun manifestYaml(
            version: String,
            entries: List<ManifestEntry>,
            releaseDate: Instant,
        ): String =
            buildString {
                appendLine("version: $version")
                appendLine("files:")
                for (entry in entries) {
                    appendLine("  - url: ${entry.url}")
                    appendLine("    sha512: ${entry.sha512}")
                    appendLine("    size: ${entry.size}")
                }
                appendLine("path: ${entries.first().url}")
                appendLine("sha512: ${entries.first().sha512}")
                appendLine("releaseDate: '$releaseDate'")
            }

        private fun sha512Base64(file: File): String {
            val digest = MessageDigest.getInstance("SHA-512")
            file.inputStream().use { input ->
                val buffer = ByteArray(BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            return Base64.getEncoder().encodeToString(digest.digest())
        }

        /** Parses `bytes=a-b`, `bytes=a-` and `bytes=-n`; `null` when it is not a single byte range. */
        private fun parseRange(
            header: String,
            length: Long,
        ): Pair<Long, Long>? {
            val spec = header.trim()
            if (!spec.startsWith("bytes=") || ',' in spec) return null
            val (first, last) = spec.removePrefix("bytes=").split('-', limit = 2).takeIf { it.size == 2 } ?: return null
            val range =
                when {
                    first.isBlank() -> {
                        val suffix = last.trim().toLongOrNull() ?: return null
                        (length - suffix).coerceAtLeast(0) to length - 1
                    }
                    else -> {
                        val start = first.trim().toLongOrNull() ?: return null
                        val end = last.trim().takeIf { it.isNotEmpty() }?.toLongOrNull() ?: (length - 1)
                        start to minOf(end, length - 1)
                    }
                }
            return if (range.first > range.second || range.first >= length) UNSATISFIABLE else range
        }

        private fun globToRegex(glob: String): Regex = Regex(glob.split('*').joinToString(".*") { Regex.escape(it) })
    }
}
