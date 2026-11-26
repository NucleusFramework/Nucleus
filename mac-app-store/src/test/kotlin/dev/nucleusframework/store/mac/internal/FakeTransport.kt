package dev.nucleusframework.store.mac.internal

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

/** A request as the fake saw it. */
internal data class SeenRequest(
    val id: Long,
    val method: String,
    val args: JsonObject,
)

/**
 * A scriptable [NativeTransport]. In [held] mode requests wait for [respond]; otherwise
 * [answer] replies from inside `request`, re-entrantly, like a native fast path.
 */
internal class FakeTransport(
    var held: Boolean = false,
    var answer: (SeenRequest) -> String = { """{"ok":null}""" },
) : NativeTransport {
    var available = true

    lateinit var receiver: NativeReceiver
    val requests = ConcurrentLinkedQueue<SeenRequest>()
    val cancels = ConcurrentLinkedQueue<Long>()
    private val waiting = ConcurrentHashMap<Long, SeenRequest>()

    override val isAvailable: Boolean
        get() = available

    var startUpdatesResult = true
    var forgetAnswer = ok("""{"removed":false,"skipped":"fake"}""")
    val forgets =
        java.util.concurrent.atomic
            .AtomicInteger()
    val startUpdatesCalls =
        java.util.concurrent.atomic
            .AtomicInteger()

    override fun attach(receiver: NativeReceiver) {
        this.receiver = receiver
    }

    override fun startUpdates(): Boolean {
        startUpdatesCalls.incrementAndGet()
        return startUpdatesResult
    }

    fun event(json: String) = receiver.onEvent(json)

    override fun request(
        id: Long,
        method: String,
        args: String,
    ) {
        val seen = SeenRequest(id, method, Json.parseToJsonElement(args).jsonObject)
        // The leftover-configuration check that precedes the first call without a .storekit; never
        // held, and kept out of [requests] so tests see only the calls they make.
        if (method == "dev.forget") {
            forgets.incrementAndGet()
            receiver.onResponse(id, forgetAnswer)
            return
        }
        requests += seen
        if (held) waiting[id] = seen else receiver.onResponse(id, answer(seen))
    }

    override fun cancel(id: Long) {
        cancels += id
        // The native side still answers a cancelled request.
        waiting.remove(id)?.let { receiver.onResponse(it.id, err("cancelled")) }
    }

    fun waitingRequests(): List<SeenRequest> = waiting.values.sortedBy { it.id }

    fun respond(
        id: Long,
        envelope: String,
    ) {
        waiting.remove(id)
        receiver.onResponse(id, envelope)
    }

    fun methods(): List<String> = requests.map { it.method }
}

internal fun ok(payload: String) = """{"ok":$payload}"""

internal fun err(
    code: String,
    message: String = code,
    platformCode: Int? = null,
) = """{"error":{"code":"$code","message":"$message"${platformCode?.let { ""","platformCode":$it""" } ?: ""}}}"""
