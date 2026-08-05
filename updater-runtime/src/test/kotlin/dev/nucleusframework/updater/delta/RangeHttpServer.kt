package dev.nucleusframework.updater.delta

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/**
 * A loopback HTTP server that serves byte ranges the way a release host does, and records how much
 * it actually sent — which is how the tests prove a differential download really transferred only
 * the changed blocks instead of merely producing the right file.
 *
 * With [honorRanges] off it answers every request with the whole body and HTTP 200, imitating a host
 * that ignores `Range`; the updater must then notice and fall back to a full download.
 */
internal class RangeHttpServer(
    private val honorRanges: Boolean = true,
) : AutoCloseable {
    private val server: HttpServer = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
    private val resources = ConcurrentHashMap<String, ByteArray>()

    /** Bytes of resource bodies sent, excluding headers. */
    val bytesServed = AtomicLong()

    /** Every request line received, as `"<method> <path>[ range]"`. */
    val requests = CopyOnWriteArrayList<String>()

    val baseUrl: String get() = "http://127.0.0.1:${server.address.port}"

    init {
        server.createContext("/") { exchange -> exchange.use { handle(it) } }
        server.start()
    }

    fun put(
        path: String,
        body: ByteArray,
    ) {
        resources[path] = body
    }

    fun remove(path: String) {
        resources.remove(path)
    }

    fun resetCounters() {
        bytesServed.set(0)
        requests.clear()
    }

    override fun close() {
        server.stop(0)
    }

    private fun handle(exchange: HttpExchange) {
        val path = exchange.requestURI.path
        val range = exchange.requestHeaders.getFirst("Range")
        requests += listOfNotNull("${exchange.requestMethod} $path", range).joinToString(" ")

        val body = resources[path]
        if (body == null) {
            exchange.sendResponseHeaders(HTTP_NOT_FOUND, -1)
            return
        }

        val parsed = range?.takeIf { honorRanges }?.let(::parseRange)
        if (parsed == null) {
            respond(exchange, HTTP_OK, body)
            return
        }

        val (start, endInclusive) = parsed
        if (start < 0 || endInclusive >= body.size || start > endInclusive) {
            exchange.responseHeaders.add("Content-Range", "bytes */${body.size}")
            exchange.sendResponseHeaders(HTTP_RANGE_NOT_SATISFIABLE, -1)
            return
        }
        exchange.responseHeaders.add("Content-Range", "bytes $start-$endInclusive/${body.size}")
        respond(exchange, HTTP_PARTIAL_CONTENT, body.copyOfRange(start.toInt(), endInclusive.toInt() + 1))
    }

    private fun respond(
        exchange: HttpExchange,
        status: Int,
        body: ByteArray,
    ) {
        exchange.sendResponseHeaders(status, body.size.toLong())
        exchange.responseBody.write(body)
        bytesServed.addAndGet(body.size.toLong())
    }

    /** Parses the single-range form the updater sends: `bytes=<start>-<endInclusive>`. */
    private fun parseRange(header: String): Pair<Long, Long>? {
        val spec = header.trim().removePrefix("bytes=").takeIf { it != header.trim() } ?: return null
        val (start, end) = spec.split('-', limit = 2).takeIf { it.size == 2 } ?: return null
        return (start.trim().toLongOrNull() ?: return null) to (end.trim().toLongOrNull() ?: return null)
    }

    private inline fun HttpExchange.use(block: (HttpExchange) -> Unit) {
        try {
            block(this)
        } finally {
            close()
        }
    }

    private companion object {
        const val HTTP_OK = 200
        const val HTTP_PARTIAL_CONTENT = 206
        const val HTTP_NOT_FOUND = 404
        const val HTTP_RANGE_NOT_SATISFIABLE = 416
    }
}
