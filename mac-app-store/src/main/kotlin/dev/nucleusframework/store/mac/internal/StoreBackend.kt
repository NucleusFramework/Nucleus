package dev.nucleusframework.store.mac.internal

import dev.nucleusframework.store.mac.StoreAvailability
import dev.nucleusframework.store.mac.StoreErrorCode
import dev.nucleusframework.store.mac.StoreException
import dev.nucleusframework.store.mac.StoreProduct
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonElement
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.logging.Logger
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** What [dev.nucleusframework.store.mac.MacAppStore] delegates to. */
internal interface StoreBackend {
    val isSupported: Boolean

    suspend fun availability(): StoreAvailability

    suspend fun products(ids: List<String>): List<StoreProduct>
}

/** Windows, Linux, or a Mac whose native bridge did not load. */
internal class UnsupportedStoreBackend(
    private val reason: String,
) : StoreBackend {
    override val isSupported: Boolean = false

    override suspend fun availability(): StoreAvailability = StoreAvailability.Unsupported

    override suspend fun products(ids: List<String>): List<StoreProduct> =
        throw StoreException(StoreErrorCode.Unsupported, reason)
}

/**
 * The native bridge's request/response protocol: every request is answered exactly once, and
 * cancelling the caller cancels the StoreKit operation.
 *
 * With a [dev] configuration, the StoreKit Testing session is installed before the first
 * request reaches StoreKit, so not even an early call sees the real store.
 */
internal class NativeStoreBackend(
    private val transport: NativeTransport,
    private val dev: DevConfiguration? = null,
) : StoreBackend,
    NativeReceiver {
    private val logger = Logger.getLogger(NativeStoreBackend::class.java.name)
    private val nextId = AtomicLong()
    private val pending = ConcurrentHashMap<Long, CancellableContinuation<String>>()
    private val devMutex = Mutex()

    @Volatile
    private var devReady = dev == null

    init {
        transport.attach(this)
    }

    override val isSupported: Boolean
        get() = transport.isAvailable

    /** Requests still waiting for their answer; for tests. */
    internal val pendingCount: Int
        get() = pending.size

    // -- transport --------------------------------------------------------------------------

    private suspend fun call(
        method: String,
        args: String,
    ): JsonElement {
        if (!transport.isAvailable) {
            throw StoreException(StoreErrorCode.Unsupported, "The Mac App Store bridge is not available")
        }
        ensureDevSession()
        return exchange(method, args)
    }

    private suspend fun ensureDevSession() {
        if (devReady) return
        val configuration = dev ?: return
        devMutex.withLock {
            if (devReady) return
            val args = jsonArgs("configuration" to configuration.configuration, "framework" to configuration.framework)
            exchange("dev.configure", args)
            devReady = true
        }
    }

    private suspend fun exchange(
        method: String,
        args: String,
    ): JsonElement {
        val id = nextId.incrementAndGet()
        val envelope =
            suspendCancellableCoroutine { continuation ->
                pending[id] = continuation
                continuation.invokeOnCancellation {
                    if (pending.remove(id) != null) transport.cancel(id)
                }
                @Suppress("TooGenericExceptionCaught")
                try {
                    transport.request(id, method, args)
                } catch (t: Throwable) {
                    if (pending.remove(id) != null) {
                        continuation.resumeWithException(internalError("Native request failed", t))
                    }
                }
            }
        val decoded = parse("response to $method") { WireJson.decodeFromString(WireEnvelope.serializer(), envelope) }
        decoded.error?.let { throw it.toException() }
        return decoded.ok ?: throw internalError("Empty native response to $method")
    }

    private suspend fun <T> decode(
        method: String,
        args: String,
        serializer: KSerializer<T>,
    ): T {
        val payload = call(method, args)
        return parse("payload for $method") { WireJson.decodeFromJsonElement(serializer, payload) }
    }

    override fun onResponse(
        id: Long,
        envelope: String,
    ) {
        // Unknown ids are answers to requests cancelled in the meantime.
        pending.remove(id)?.resume(envelope)
    }

    override fun onEvent(json: String) {
        logger.fine { "Ignoring Mac App Store event $json" }
    }

    // -- API --------------------------------------------------------------------------------

    override suspend fun availability(): StoreAvailability {
        if (!transport.isAvailable) return StoreAvailability.Unsupported
        return availability(decode("availability", "{}", WireAvailability.serializer()).state)
    }

    override suspend fun products(ids: List<String>): List<StoreProduct> {
        val wanted = ids.filter { it.isNotBlank() }.distinct()
        if (wanted.isEmpty()) return emptyList()
        val products =
            decode("products", jsonArgs("ids" to wanted), ListSerializer(WireProduct.serializer()))
                .map { it.toProduct() }
        // Keep the caller's order and drop anything not asked for or listed twice.
        val byId = products.associateBy { it.id }
        return wanted.mapNotNull(byId::get)
    }
}

private fun internalError(
    message: String,
    cause: Throwable? = null,
) = StoreException(StoreErrorCode.Internal, message, cause = cause)

/** Decodes with [block]; anything the JSON layer rejects is an [StoreErrorCode.Internal] error. */
private inline fun <T> parse(
    what: String,
    block: () -> T,
): T =
    try {
        block()
    } catch (e: IllegalArgumentException) {
        // SerializationException is an IllegalArgumentException.
        throw internalError("Malformed native $what", e)
    }
