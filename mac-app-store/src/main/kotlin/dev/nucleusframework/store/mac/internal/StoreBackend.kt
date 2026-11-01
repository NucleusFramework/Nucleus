package dev.nucleusframework.store.mac.internal

import dev.nucleusframework.store.mac.AppTransactionInfo
import dev.nucleusframework.store.mac.EntitlementStatus
import dev.nucleusframework.store.mac.ProductType
import dev.nucleusframework.store.mac.Purchase
import dev.nucleusframework.store.mac.PurchaseOptions
import dev.nucleusframework.store.mac.PurchaseResult
import dev.nucleusframework.store.mac.PurchaseUpdate
import dev.nucleusframework.store.mac.StoreAvailability
import dev.nucleusframework.store.mac.StoreErrorCode
import dev.nucleusframework.store.mac.StoreException
import dev.nucleusframework.store.mac.StoreProduct
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonElement
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.logging.Level
import java.util.logging.Logger
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** What [dev.nucleusframework.store.mac.MacAppStore] delegates to. */
internal interface StoreBackend {
    val isSupported: Boolean
    val updates: Flow<PurchaseUpdate>

    /** A StoreKit configuration serves the store. */
    val isTesting: Boolean

    suspend fun availability(): StoreAvailability

    suspend fun products(ids: List<String>): List<StoreProduct>

    suspend fun purchase(
        productId: String,
        options: PurchaseOptions,
    ): PurchaseResult

    suspend fun purchases(): List<Purchase>

    suspend fun entitlement(productId: String): EntitlementStatus

    suspend fun consume(purchase: Purchase)

    suspend fun appTransaction(): AppTransactionInfo

    suspend fun openStorePage(appId: Long?)

    /** Runs a `dev.*` method of the StoreKit Testing session. */
    suspend fun devCommand(
        method: String,
        args: String,
    )
}

/** Windows, Linux, or a Mac whose native bridge did not load. */
internal class UnsupportedStoreBackend(
    private val reason: String,
) : StoreBackend {
    override val isSupported: Boolean = false
    override val updates: Flow<PurchaseUpdate> = emptyFlow()
    override val isTesting: Boolean = false

    override suspend fun availability(): StoreAvailability = StoreAvailability.Unsupported

    private fun unsupported(): Nothing = throw StoreException(StoreErrorCode.Unsupported, reason)

    override suspend fun products(ids: List<String>): List<StoreProduct> = unsupported()

    override suspend fun purchase(
        productId: String,
        options: PurchaseOptions,
    ): PurchaseResult = PurchaseResult.Failed(StoreException(StoreErrorCode.Unsupported, reason))

    override suspend fun purchases(): List<Purchase> = unsupported()

    override suspend fun entitlement(productId: String): EntitlementStatus = unsupported()

    override suspend fun consume(purchase: Purchase): Unit = unsupported()

    override suspend fun appTransaction(): AppTransactionInfo = unsupported()

    override suspend fun openStorePage(appId: Long?): Unit = unsupported()

    override suspend fun devCommand(
        method: String,
        args: String,
    ): Unit = unsupported()
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
    private val listening = AtomicBoolean()

    // Unbounded: events come from native threads that cannot suspend, and an update must not be
    // dropped because a collector is slow.
    private val updateFlow = MutableSharedFlow<PurchaseUpdate>(extraBufferCapacity = Int.MAX_VALUE)

    @Volatile
    private var devReady = dev == null

    init {
        transport.attach(this)
    }

    override val isSupported: Boolean
        get() = transport.isAvailable

    override val updates: Flow<PurchaseUpdate> = updateFlow.onSubscription { ensureListening() }

    override val isTesting: Boolean
        get() = dev != null && transport.isAvailable

    // On the first collector, after the development session (a listener started before it would
    // watch the real store). StoreKit replays unfinished transactions to the new listener.
    private suspend fun ensureListening() {
        if (!transport.isAvailable || !listening.compareAndSet(false, true)) return
        try {
            ensureDevSession()
        } catch (e: StoreException) {
            logger.log(Level.WARNING, "No StoreKit configuration for purchase updates", e)
        }
        if (!transport.startUpdates()) logger.fine { "No transaction updates outside an app bundle" }
    }

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
        val event =
            try {
                parse("event") { WireJson.decodeFromString(WireEvent.serializer(), json) }
            } catch (e: StoreException) {
                logger.log(Level.WARNING, "Malformed Mac App Store event", e)
                return
            }
        when (event.type) {
            "transaction" -> {
                val purchase = event.purchase?.toPurchase() ?: return
                updateFlow.tryEmit(
                    if (purchase.revocationTime !=
                        null
                    ) {
                        PurchaseUpdate.Revoked(purchase)
                    } else {
                        PurchaseUpdate.Purchased(purchase)
                    },
                )
            }
            else -> logger.fine { "Ignoring Mac App Store event '${event.type}'" }
        }
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

    override suspend fun purchase(
        productId: String,
        options: PurchaseOptions,
    ): PurchaseResult {
        if (productId.isBlank()) return failed(StoreErrorCode.InvalidArgument, "productId must not be blank")
        if (options.quantity < 1) return failed(StoreErrorCode.InvalidArgument, "quantity must be at least 1")
        val args =
            jsonArgs(
                "productId" to productId,
                "window" to options.window,
                "quantity" to options.quantity,
                "appAccountToken" to options.appAccountToken?.toString(),
            )
        val outcome =
            try {
                decode("purchase", args, WirePurchaseOutcome.serializer())
            } catch (e: StoreException) {
                return PurchaseResult.Failed(e)
            }
        return when (outcome.status) {
            "success" ->
                outcome.purchase?.let { PurchaseResult.Success(it.toPurchase()) }
                    ?: failed(StoreErrorCode.Internal, "Purchase succeeded without a transaction")
            "pending" -> PurchaseResult.Pending
            "cancelled" -> PurchaseResult.Cancelled
            else -> failed(StoreErrorCode.Internal, "Unknown purchase status '${outcome.status}'")
        }
    }

    override suspend fun purchases(): List<Purchase> =
        decode("purchases", "{}", ListSerializer(WirePurchase.serializer())).map { it.toPurchase() }

    override suspend fun entitlement(productId: String): EntitlementStatus {
        if (productId.isBlank()) throw StoreException(StoreErrorCode.InvalidArgument, "productId must not be blank")
        return decode("entitlement", jsonArgs("productId" to productId), WireEntitlement.serializer()).toStatus()
    }

    override suspend fun consume(purchase: Purchase) {
        if (purchase.productType != ProductType.Consumable) {
            throw StoreException(
                StoreErrorCode.InvalidArgument,
                "${purchase.productId} is a ${purchase.productType}, not a consumable",
            )
        }
        call("consume", jsonArgs("transactionId" to purchase.transactionId))
    }

    override suspend fun appTransaction(): AppTransactionInfo =
        decode("appTransaction", "{}", WireAppTransaction.serializer()).toInfo()

    override suspend fun openStorePage(appId: Long?) {
        if (appId != null && appId <= 0) throw StoreException(StoreErrorCode.InvalidArgument, "appId must be positive")
        if (!decode("openStorePage", jsonArgs("appId" to appId?.toString()), WireOpened.serializer()).opened) {
            throw StoreException(StoreErrorCode.Platform, "macOS did not open the App Store")
        }
    }

    override suspend fun devCommand(
        method: String,
        args: String,
    ) {
        if (dev == null) {
            throw StoreException(
                StoreErrorCode.InvalidArgument,
                "No StoreKit configuration is active: launch with -D$STOREKIT_CONFIGURATION_PROPERTY=<file.storekit>",
            )
        }
        call(method, args)
    }
}

private fun failed(
    code: StoreErrorCode,
    message: String,
) = PurchaseResult.Failed(StoreException(code, message))

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
