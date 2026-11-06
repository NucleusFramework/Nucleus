package dev.nucleusframework.store.microsoft.internal

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** A request as the fake saw it. */
internal data class SeenRequest(
    val id: Long,
    val method: String,
    val args: JsonObject,
)

/**
 * A scriptable [NativeTransport]. [answer] builds the envelope; [mode] decides when and on which
 * thread it is delivered, which is what the concurrency tests vary.
 */
internal class FakeTransport(
    var mode: Mode = Mode.Sync,
    var answer: (SeenRequest) -> String = { """{"ok":null}""" },
) : NativeTransport {
    enum class Mode {
        /** Answer from inside `request`, re-entrantly, like a native fast path. */
        Sync,

        /** Answer later, from a pool thread, after 0–[maxDelayMillis] ms: out of order. */
        Async,

        /** Never answer on its own; the test calls [respond]. */
        Held,
    }

    var available = true
    var maxDelayMillis = 5L
    var startUpdatesResult = true
    var throwOnRequest: Throwable? = null

    /** Whether a cancel is answered with a `cancelled` error, as the native side does. */
    var answerCancels = true

    lateinit var receiver: NativeReceiver
    val requests = ConcurrentLinkedQueue<SeenRequest>()
    val cancels = ConcurrentLinkedQueue<Long>()
    val startUpdatesCalls = AtomicInteger()
    private val held = ConcurrentHashMap<Long, SeenRequest>()
    private val pool = Executors.newScheduledThreadPool(8) { r -> Thread(r, "fake-native").apply { isDaemon = true } }

    override val isAvailable: Boolean
        get() = available

    override fun attach(receiver: NativeReceiver) {
        this.receiver = receiver
    }

    override fun startUpdates(): Boolean {
        startUpdatesCalls.incrementAndGet()
        return startUpdatesResult
    }

    override fun request(
        id: Long,
        method: String,
        args: String,
    ) {
        throwOnRequest?.let { throw it }
        val seen = SeenRequest(id, method, Json.parseToJsonElement(args).jsonObject)
        requests += seen
        when (mode) {
            Mode.Sync -> receiver.onResponse(id, answer(seen))
            Mode.Async -> {
                held[id] = seen
                val delay = ThreadLocalRandom.current().nextLong(maxDelayMillis + 1)
                pool.schedule({
                    if (held.remove(id) !=
                        null
                    ) {
                        receiver.onResponse(id, answer(seen))
                    }
                }, delay, TimeUnit.MILLISECONDS)
            }
            Mode.Held -> held[id] = seen
        }
    }

    override fun cancel(id: Long) {
        cancels += id
        val seen = held.remove(id) ?: return
        if (answerCancels) {
            pool.execute { receiver.onResponse(seen.id, """{"error":{"code":"cancelled","message":"cancelled"}}""") }
        }
    }

    fun heldRequests(): List<SeenRequest> = held.values.toList()

    /** Answers a [Mode.Held] request. */
    fun respond(
        id: Long,
        envelope: String,
    ) {
        held.remove(id)
        receiver.onResponse(id, envelope)
    }

    fun event(json: String) = receiver.onEvent(json)
}

internal fun ok(payload: String) = """{"ok":$payload}"""

internal fun err(
    code: String,
    message: String = code,
    hresult: Long? = null,
) = """{"error":{"code":"$code","message":"$message"${hresult?.let { ""","hresult":$it""" } ?: ""}}}"""
