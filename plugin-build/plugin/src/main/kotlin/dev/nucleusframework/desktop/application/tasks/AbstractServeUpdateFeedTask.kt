package dev.nucleusframework.desktop.application.tasks

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import dev.nucleusframework.desktop.application.internal.UpdateYmlPublish
import dev.nucleusframework.desktop.tasks.AbstractNucleusTask
import dev.nucleusframework.internal.utils.notNullProperty
import dev.nucleusframework.internal.utils.nullableProperty
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.io.RandomAccessFile
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Serves the packaged update of the current OS over loopback HTTP, as a release host would, so an
 * installed copy of the app can update to it with nothing published:
 *
 * ```
 * ./gradlew serveUpdateFeed                        # packages, then serves http://127.0.0.1:8421
 * NUCLEUS_UPDATER_FEED_URL=http://127.0.0.1:8421 <installed app>
 * ```
 *
 * The feed is the union of the per-format packaging outputs (the same manifests the release would
 * publish, merged when several formats share one), with the artifacts, block maps and detached
 * signatures next to them. Byte ranges are served, so differential downloads work as in production.
 * `-Pnucleus.updater.serve.throttle=<bytes per second>` and `-Pnucleus.updater.serve.latency=<ms>`
 * slow it down, to watch the app's progress UI; `-Pnucleus.updater.serve.timeout=<seconds>` stops
 * it on its own (otherwise it serves until the build is cancelled).
 */
@DisableCachingByDefault(because = "Runs a server, not a cacheable build step")
abstract class AbstractServeUpdateFeedTask : AbstractNucleusTask() {
    /** Output directories of the current OS's auto-updatable package tasks. */
    @get:Internal
    val perFormatOutputDirs: ConfigurableFileCollection = objects.fileCollection()

    @get:Input
    val port: Property<Int> = objects.notNullProperty<Int>().apply { set(DEFAULT_PORT) }

    @get:Input
    @get:Optional
    val throttleBytesPerSecond: Property<Long> = objects.nullableProperty()

    @get:Input
    @get:Optional
    val latencyMillis: Property<Long> = objects.nullableProperty()

    @get:Input
    @get:Optional
    val timeoutSeconds: Property<Long> = objects.nullableProperty()

    @TaskAction
    fun serve() {
        val dirs = perFormatOutputDirs.files.filter(File::isDirectory)
        val manifests = UpdateYmlPublish.discoverAndMerge(dirs).associate { it.fileName to it.content.toByteArray() }
        if (manifests.isEmpty()) {
            throw GradleException(
                "No update manifest in ${dirs.joinToString()}: package an auto-updatable format first " +
                    "(NSIS, MSI, DMG, macOS ZIP, AppImage, DEB, RPM).",
            )
        }
        val executor = Executors.newCachedThreadPool { runnable -> Thread(runnable, "nucleus-update-feed").apply { isDaemon = true } }
        val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), port.get()), 0)
        server.executor = executor
        server.createContext("/") { exchange -> handle(exchange, dirs, manifests) }
        server.start()
        val url = "http://127.0.0.1:${server.address.port}"
        logger.lifecycle(
            buildString {
                appendLine("Serving the update feed at $url")
                manifests.forEach { (name, content) ->
                    val version = String(content).lineSequence().firstOrNull { it.startsWith("version:") }?.substringAfter(':')?.trim()
                    appendLine("  $name → $version")
                }
                appendLine("Point the app at it with NUCLEUS_UPDATER_FEED_URL=$url")
                appendLine("  (an installed app must set UpdaterConfig.allowLaunchOverrides; ./gradlew run -Pnucleus.updater.feedUrl=$url always works)")
                append("Cancel the build (Ctrl+C) to stop.")
            },
        )
        try {
            val timeout = timeoutSeconds.orNull
            if (timeout != null) Thread.sleep(TimeUnit.SECONDS.toMillis(timeout)) else Thread.sleep(Long.MAX_VALUE)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } finally {
            server.stop(0)
            executor.shutdownNow()
            logger.lifecycle("Update feed stopped.")
        }
    }

    private fun handle(
        exchange: HttpExchange,
        dirs: List<File>,
        manifests: Map<String, ByteArray>,
    ) {
        try {
            latencyMillis.orNull?.let(Thread::sleep)
            val name = exchange.requestURI.path.trimStart('/')
            val range = exchange.requestHeaders.getFirst("Range")
            logger.lifecycle("${exchange.requestMethod} /$name${range?.let { " [$it]" }.orEmpty()}")
            if (exchange.requestMethod !in setOf("GET", "HEAD") || '/' in name || '\\' in name || name.startsWith("..")) {
                exchange.sendResponseHeaders(HTTP_NOT_FOUND, -1)
                return
            }
            manifests[name]?.let { body ->
                exchange.responseHeaders.add("Content-Type", "text/yaml")
                exchange.sendResponseHeaders(HTTP_OK, body.size.toLong())
                exchange.responseBody.write(body)
                return
            }
            val file = dirs.map { File(it, name) }.firstOrNull(File::isFile)
            if (file == null) {
                exchange.sendResponseHeaders(HTTP_NOT_FOUND, -1)
                return
            }
            serveFile(exchange, file, range)
        } catch (_: IOException) {
            // The client went away.
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } finally {
            exchange.close()
        }
    }

    private fun serveFile(
        exchange: HttpExchange,
        file: File,
        range: String?,
    ) {
        val length = file.length()
        val requested = range?.let { parseRange(it, length) }
        exchange.responseHeaders.add("Accept-Ranges", "bytes")
        if (requested == UNSATISFIABLE) {
            exchange.responseHeaders.add("Content-Range", "bytes */$length")
            exchange.sendResponseHeaders(HTTP_RANGE_NOT_SATISFIABLE, -1)
            return
        }
        val (start, endInclusive) = requested ?: (0L to length - 1)
        val count = endInclusive - start + 1
        val status = if (requested != null) HTTP_PARTIAL_CONTENT else HTTP_OK
        if (requested != null) exchange.responseHeaders.add("Content-Range", "bytes $start-$endInclusive/$length")
        if (exchange.requestMethod == "HEAD") {
            exchange.responseHeaders.add("Content-Length", count.toString())
            exchange.sendResponseHeaders(status, -1)
            return
        }
        exchange.sendResponseHeaders(status, if (count == 0L) -1 else count)
        if (count > 0) copy(file, start, count, exchange.responseBody)
    }

    private fun copy(
        file: File,
        start: Long,
        count: Long,
        out: OutputStream,
    ) {
        val rate = throttleBytesPerSecond.orNull?.takeIf { it > 0 }
        val chunk = rate?.let { (it / THROTTLE_TICKS_PER_SECOND).coerceIn(1, BUFFER_SIZE.toLong()).toInt() } ?: BUFFER_SIZE
        val buffer = ByteArray(chunk)
        val began = System.nanoTime()
        var sent = 0L
        RandomAccessFile(file, "r").use { input ->
            input.seek(start)
            while (sent < count) {
                val read = input.read(buffer, 0, minOf(chunk.toLong(), count - sent).toInt())
                if (read < 0) break
                out.write(buffer, 0, read)
                sent += read
                if (rate != null) {
                    val aheadMillis = (sent * NANOS_PER_SECOND / rate - (System.nanoTime() - began)) / NANOS_PER_MILLI
                    if (aheadMillis > 0) Thread.sleep(aheadMillis)
                }
            }
        }
    }

    internal companion object {
        const val DEFAULT_PORT = 8421
        private const val HTTP_OK = 200
        private const val HTTP_PARTIAL_CONTENT = 206
        private const val HTTP_NOT_FOUND = 404
        private const val HTTP_RANGE_NOT_SATISFIABLE = 416
        private const val BUFFER_SIZE = 64 * 1024
        private const val THROTTLE_TICKS_PER_SECOND = 20
        private const val NANOS_PER_SECOND = 1_000_000_000L
        private const val NANOS_PER_MILLI = 1_000_000L
        private val UNSATISFIABLE = -1L to -1L

        /** Parses a single `bytes=a-b`, `bytes=a-` or `bytes=-n` range; `null` for anything else. */
        fun parseRange(
            header: String,
            length: Long,
        ): Pair<Long, Long>? {
            val spec = header.trim()
            if (!spec.startsWith("bytes=") || ',' in spec) return null
            val parts = spec.removePrefix("bytes=").split('-', limit = 2)
            if (parts.size != 2) return null
            val (first, last) = parts
            val range =
                if (first.isBlank()) {
                    val suffix = last.trim().toLongOrNull() ?: return null
                    (length - suffix).coerceAtLeast(0) to length - 1
                } else {
                    val begin = first.trim().toLongOrNull() ?: return null
                    val end = last.trim().takeIf { it.isNotEmpty() }?.toLongOrNull() ?: (length - 1)
                    begin to minOf(end, length - 1)
                }
            return if (range.first > range.second || range.first >= length) UNSATISFIABLE else range
        }
    }
}
