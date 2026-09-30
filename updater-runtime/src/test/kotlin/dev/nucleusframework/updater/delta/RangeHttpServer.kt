package dev.nucleusframework.updater.delta

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
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
    private val executor: ExecutorService = Executors.newCachedThreadPool()
    private val resources = ConcurrentHashMap<String, ByteArray>()
    private val redirects = ConcurrentHashMap<String, ConcurrentLinkedQueue<String>>()
    private val scripted = ConcurrentHashMap<String, ConcurrentLinkedQueue<ScriptedResponse>>()
    private val scriptDelays = ConcurrentHashMap<String, AtomicInteger>()
    private val inFlight = AtomicInteger()

    /** A canned response returned instead of the resource, for simulating failures. */
    private class ScriptedResponse(
        val status: Int,
        val retryAfter: String?,
    )

    /** Delay before answering each ranged request, so concurrent requests actually overlap. */
    @Volatile
    var rangeLatencyMs: Long = 0

    /** The most requests this server has been handling at the same time. */
    val maxInFlight = AtomicInteger()

    /** Paths of requests that carried an `Authorization` header. */
    val authorizedRequests = CopyOnWriteArrayList<String>()

    /** Bytes of resource bodies sent, excluding headers. */
    val bytesServed = AtomicLong()

    /** Every request line received, as `"<method> <path>[ range]"`. */
    val requests = CopyOnWriteArrayList<String>()

    val baseUrl: String get() = "http://127.0.0.1:${server.address.port}"

    init {
        server.createContext("/") { exchange -> exchange.use { handle(it) } }
        server.executor = executor
        server.start()
    }

    /**
     * Answers [times] requests for [path] with [status] instead of the resource, once [after]
     * requests for it have been served normally.
     */
    fun failNext(
        path: String,
        status: Int,
        times: Int = 1,
        retryAfter: String? = null,
        after: Int = 0,
    ) {
        scriptDelays[path] = AtomicInteger(after)
        val queue = scripted.getOrPut(path) { ConcurrentLinkedQueue() }
        repeat(times) { queue += ScriptedResponse(status, retryAfter) }
    }

    /**
     * Redirects requests for [from] with HTTP 302, as a release host does to its CDN: to each of [to]
     * in turn, staying on the last one — like a host handing out a fresh pre-signed URL.
     */
    fun redirect(
        from: String,
        vararg to: String,
    ) {
        redirects[from] = ConcurrentLinkedQueue(to.toList())
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
        executor.shutdownNow()
    }

    private fun handle(exchange: HttpExchange) {
        maxInFlight.accumulateAndGet(inFlight.incrementAndGet(), ::maxOf)
        try {
            serve(exchange)
        } finally {
            inFlight.decrementAndGet()
        }
    }

    private fun serve(exchange: HttpExchange) {
        val path = exchange.requestURI.path
        val range = exchange.requestHeaders.getFirst("Range")
        requests += listOfNotNull("${exchange.requestMethod} $path", range).joinToString(" ")
        if (exchange.requestHeaders.containsKey("Authorization")) authorizedRequests += path

        val scriptDue = (scriptDelays[path]?.getAndDecrement() ?: 0) <= 0
        scripted[path]?.takeIf { scriptDue }?.poll()?.let { response ->
            response.retryAfter?.let { exchange.responseHeaders.add("Retry-After", it) }
            exchange.sendResponseHeaders(response.status, -1)
            return
        }
        redirects[path]?.let { targets ->
            val target = if (targets.size > 1) targets.poll() else targets.peek()
            exchange.responseHeaders.add("Location", target)
            exchange.sendResponseHeaders(HTTP_FOUND, -1)
            return
        }
        if (range != null && rangeLatencyMs > 0) Thread.sleep(rangeLatencyMs)

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
        const val HTTP_FOUND = 302
        const val HTTP_NOT_FOUND = 404
        const val HTTP_RANGE_NOT_SATISFIABLE = 416
    }
}
