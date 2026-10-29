package dev.nucleusframework.iap.internal

import dev.nucleusframework.iap.AppLicense
import dev.nucleusframework.iap.EntitlementStatus
import dev.nucleusframework.iap.ProductType
import dev.nucleusframework.iap.Purchase
import dev.nucleusframework.iap.PurchaseOptions
import dev.nucleusframework.iap.PurchaseResult
import dev.nucleusframework.iap.PurchaseState
import dev.nucleusframework.iap.PurchaseUpdate
import dev.nucleusframework.iap.StoreAvailability
import dev.nucleusframework.iap.StoreErrorCode
import dev.nucleusframework.iap.StoreException
import dev.nucleusframework.iap.StoreProduct
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
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

/** What [dev.nucleusframework.iap.InAppPurchase] delegates to. */
internal interface StoreBackend {
    val isSupported: Boolean
    val updates: Flow<PurchaseUpdate>
    val appLicenseUpdates: Flow<AppLicense>

    suspend fun availability(): StoreAvailability

    suspend fun products(ids: List<String>): List<StoreProduct>

    suspend fun purchase(
        productId: String,
        options: PurchaseOptions,
    ): PurchaseResult

    suspend fun purchases(): List<Purchase>

    suspend fun restorePurchases(): List<Purchase>

    suspend fun entitlement(productId: String): EntitlementStatus

    suspend fun consume(
        purchase: Purchase,
        quantity: Int,
    )

    suspend fun appLicense(): AppLicense

    suspend fun purchaseApp(options: PurchaseOptions): PurchaseResult
}

/** Linux, or a platform whose native bridge did not load. */
internal class UnsupportedStoreBackend(
    private val reason: String,
) : StoreBackend {
    override val isSupported: Boolean = false
    override val updates: Flow<PurchaseUpdate> = emptyFlow()
    override val appLicenseUpdates: Flow<AppLicense> = emptyFlow()

    private fun unsupported(): Nothing = throw StoreException(StoreErrorCode.Unsupported, reason)

    override suspend fun availability(): StoreAvailability = StoreAvailability.Unsupported

    override suspend fun products(ids: List<String>): List<StoreProduct> = unsupported()

    override suspend fun purchase(
        productId: String,
        options: PurchaseOptions,
    ): PurchaseResult = PurchaseResult.Failed(StoreException(StoreErrorCode.Unsupported, reason))

    override suspend fun purchases(): List<Purchase> = unsupported()

    override suspend fun restorePurchases(): List<Purchase> = unsupported()

    override suspend fun entitlement(productId: String): EntitlementStatus = unsupported()

    override suspend fun consume(
        purchase: Purchase,
        quantity: Int,
    ): Unit = unsupported()

    override suspend fun appLicense(): AppLicense = unsupported()

    override suspend fun purchaseApp(options: PurchaseOptions): PurchaseResult =
        PurchaseResult.Failed(StoreException(StoreErrorCode.Unsupported, reason))
}

/** A native answer: the `ok` payload, or the `error` the backend reported. */
private sealed interface Answer {
    class Ok(
        val payload: JsonElement,
    ) : Answer

    class Err(
        val error: WireError,
    ) : Answer
}

/**
 * The native backend's request/response protocol. Out-of-band updates arrive as license
 * snapshots (diffed by [LicenseTracker]) or, from a store that reports them, whole transactions.
 */
internal class NativeStoreBackend(
    private val transport: NativeTransport,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val licenses: LicenseTracker = LicenseTracker(),
) : StoreBackend,
    NativeReceiver {
    private val logger = Logger.getLogger(NativeStoreBackend::class.java.name)
    private val nextId = AtomicLong()
    private val pending = ConcurrentHashMap<Long, kotlinx.coroutines.CancellableContinuation<String>>()
    private val listening = AtomicBoolean()

    // Unbounded: events come from native threads that cannot suspend, and an update must not
    // be dropped because a collector is slow.
    private val updateFlow = MutableSharedFlow<PurchaseUpdate>(extraBufferCapacity = Int.MAX_VALUE)
    private val appLicenseFlow = MutableSharedFlow<AppLicense>(extraBufferCapacity = Int.MAX_VALUE)

    // The last app license known, so an add-on change does not repeat an unchanged one.
    private var lastAppLicense: AppLicense? = null

    init {
        transport.attach(this)
    }

    override val isSupported: Boolean
        get() = transport.isAvailable

    override val updates: Flow<PurchaseUpdate> = updateFlow.onSubscription { ensureListening() }

    override val appLicenseUpdates: Flow<AppLicense> = appLicenseFlow.onSubscription { ensureListening() }

    /** Requests still waiting for their answer; for tests. */
    internal val pendingCount: Int
        get() = pending.size

    @Synchronized
    private fun seedAppLicense(license: AppLicense) {
        if (lastAppLicense == null) lastAppLicense = license
    }

    @Synchronized
    private fun reportAppLicense(license: AppLicense) {
        // Trial time ticks down between snapshots; only a change of state is news.
        val previous = lastAppLicense
        lastAppLicense = license
        if (previous?.withoutCountdown() != license.withoutCountdown()) appLicenseFlow.tryEmit(license)
    }

    private fun AppLicense.withoutCountdown() = copy(trialTimeRemaining = null)

    private fun ensureListening() {
        if (!listening.compareAndSet(false, true)) return
        if (!transport.startUpdates()) return
        // The Microsoft Store only says "something changed": take the state to diff against.
        scope.launch {
            runCatching { decodeList("purchases", "{}", WirePurchase.serializer()) }
                .onSuccess { list -> licenses.seed(list.map { it.toPurchase() }) }
                .onFailure { logger.log(Level.FINE, "No license baseline for purchase updates", it) }
        }
        scope.launch {
            runCatching { appLicense() }
                .onSuccess(::seedAppLicense)
                .onFailure { logger.log(Level.FINE, "No baseline for app license updates", it) }
        }
    }

    // -- transport --------------------------------------------------------------------------

    private suspend fun call(
        method: String,
        args: String,
    ): Answer {
        if (!transport.isAvailable) {
            throw StoreException(StoreErrorCode.Unsupported, "The in-app purchase bridge is not available")
        }
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
        decoded.error?.let { return Answer.Err(it) }
        return Answer.Ok(decoded.ok ?: throw internalError("Empty native response to $method"))
    }

    private suspend fun <T> decode(
        method: String,
        args: String,
        serializer: KSerializer<T>,
    ): T =
        when (val answer = call(method, args)) {
            is Answer.Err -> throw answer.error.toException()
            is Answer.Ok -> parse("payload for $method") { WireJson.decodeFromJsonElement(serializer, answer.payload) }
        }

    private suspend fun <T> decodeList(
        method: String,
        args: String,
        serializer: KSerializer<T>,
    ): List<T> = decode(method, args, ListSerializer(serializer))

    /** Runs a native `diag.*` method; its `ok` payload, or the error it reported. For tests. */
    internal suspend fun diagnostic(
        method: String,
        args: String,
    ): JsonElement =
        when (val answer = call(method, args)) {
            is Answer.Ok -> answer.payload
            is Answer.Err -> throw answer.error.toException()
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
                logger.log(Level.WARNING, "Malformed in-app purchase event", e)
                return
            }
        when (event.type) {
            "transaction" -> {
                val purchase = event.purchase?.toPurchase() ?: return
                val revoked = purchase.state == PurchaseState.Revoked || purchase.revocationTime != null
                updateFlow.tryEmit(
                    if (revoked) PurchaseUpdate.Revoked(purchase) else PurchaseUpdate.Purchased(purchase),
                )
            }
            "licensesChanged" -> {
                logger.fine {
                    "Microsoft Store licenses changed: app ${event.appLicense?.skuStoreId ?: "unread"}, " +
                        "add-ons ${event.purchases.orEmpty().map { it.productId }}"
                }
                event.appLicense?.toAppLicense()?.let(::reportAppLicense)
                licenses
                    .onSnapshot(event.purchases.orEmpty().map { it.toPurchase() })
                    .forEach { updateFlow.tryEmit(it) }
            }
            else -> logger.fine { "Ignoring in-app purchase event '${event.type}'" }
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
            decodeList(
                "products",
                jsonArgs("ids" to wanted),
                WireProduct.serializer(),
            ).map { it.toProduct() }
        // Keep the caller's order and drop anything not asked for or listed twice.
        val byId = products.associateBy { it.id }
        return wanted.mapNotNull(byId::get)
    }

    override suspend fun purchase(
        productId: String,
        options: PurchaseOptions,
    ): PurchaseResult {
        if (productId.isBlank()) {
            return PurchaseResult.Failed(StoreException(StoreErrorCode.InvalidArgument, "productId must not be blank"))
        }
        val args =
            jsonArgs(
                "productId" to productId,
                "ownerWindow" to options.windows.ownerWindowHandle,
                "skuStoreId" to options.windows.skuStoreId,
            )
        licenses.beginPurchase(productId)
        return try {
            callPurchase("purchase", args)
        } finally {
            licenses.endPurchase(productId)
        }
    }

    /** Runs a purchase-shaped request; store failures become [PurchaseResult.Failed]. */
    private suspend fun callPurchase(
        method: String,
        args: String,
    ): PurchaseResult =
        try {
            when (val answer = call(method, args)) {
                is Answer.Err ->
                    if (answer.error.code == "cancelled") {
                        PurchaseResult.Cancelled
                    } else {
                        PurchaseResult.Failed(answer.error.toException())
                    }
                is Answer.Ok -> purchaseResult(answer.payload)
            }
        } catch (e: StoreException) {
            PurchaseResult.Failed(e)
        }

    override suspend fun appLicense(): AppLicense =
        decode("appLicense", "{}", WireAppLicense.serializer()).toAppLicense()

    override suspend fun purchaseApp(options: PurchaseOptions): PurchaseResult =
        callPurchase("purchaseApp", jsonArgs("ownerWindow" to options.windows.ownerWindowHandle))

    private fun purchaseResult(payload: JsonElement): PurchaseResult {
        val outcome =
            parse("purchase payload") { WireJson.decodeFromJsonElement(WirePurchaseOutcome.serializer(), payload) }
        return when (outcome.status) {
            "success", "alreadyOwned" -> {
                val purchase =
                    outcome.purchase?.toPurchase()
                        ?: return PurchaseResult.Failed(internalError("Purchase succeeded without a transaction"))
                licenses.recordPurchase(purchase)
                PurchaseResult.Success(purchase, alreadyOwned = outcome.status == "alreadyOwned")
            }
            "pending" -> PurchaseResult.Pending
            "cancelled" -> PurchaseResult.Cancelled
            else ->
                PurchaseResult.Failed(
                    outcome.error?.toException() ?: internalError("Unknown purchase status '${outcome.status}'"),
                )
        }
    }

    override suspend fun purchases(): List<Purchase> =
        decodeList("purchases", "{}", WirePurchase.serializer()).map { it.toPurchase() }

    override suspend fun restorePurchases(): List<Purchase> =
        decodeList("restore", "{}", WirePurchase.serializer()).map { it.toPurchase() }

    override suspend fun entitlement(productId: String): EntitlementStatus {
        if (productId.isBlank()) invalidArgument("productId must not be blank")
        return decode("entitlement", jsonArgs("productId" to productId), WireEntitlement.serializer()).toStatus()
    }

    override suspend fun consume(
        purchase: Purchase,
        quantity: Int,
    ) {
        if (quantity < 1) invalidArgument("quantity must be at least 1")
        if (purchase.productType != ProductType.Consumable) {
            invalidArgument("${purchase.productId} is a ${purchase.productType}, not a consumable")
        }
        val token = purchase.consumeToken ?: invalidArgument("${purchase.productId} carries no consume token")
        decode("consume", jsonArgs("token" to token, "quantity" to quantity), WireConsumeResult.serializer())
    }
}

private fun internalError(
    message: String,
    cause: Throwable? = null,
) = StoreException(StoreErrorCode.Internal, message, cause = cause)

private fun invalidArgument(message: String): Nothing = throw StoreException(StoreErrorCode.InvalidArgument, message)

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
