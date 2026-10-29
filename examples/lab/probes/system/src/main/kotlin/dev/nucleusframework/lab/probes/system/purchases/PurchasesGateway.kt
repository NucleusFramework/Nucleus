package dev.nucleusframework.lab.probes.system.purchases

import dev.nucleusframework.iap.AppLicense
import dev.nucleusframework.iap.EntitlementStatus
import dev.nucleusframework.iap.InAppPurchase
import dev.nucleusframework.iap.Purchase
import dev.nucleusframework.iap.PurchaseResult
import dev.nucleusframework.iap.PurchaseUpdate
import dev.nucleusframework.iap.StoreAvailability
import dev.nucleusframework.iap.StoreProduct
import dev.nucleusframework.lab.core.Availability
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger

/** Port over `in-app-purchase`. Store failures surface as `StoreException`, as the module throws them. */
interface PurchasesGateway {
    fun supported(): Availability

    suspend fun availability(): StoreAvailability

    suspend fun appLicense(): AppLicense

    suspend fun purchaseApp(hwnd: Long): PurchaseResult

    suspend fun products(ids: List<String>): List<StoreProduct>

    suspend fun purchase(
        productId: String,
        hwnd: Long,
    ): PurchaseResult

    suspend fun purchases(): List<Purchase>

    suspend fun restorePurchases(): List<Purchase>

    suspend fun entitlement(productId: String): EntitlementStatus

    suspend fun consume(purchase: Purchase)

    fun appLicenseUpdates(): Flow<AppLicense>

    fun purchaseUpdates(): Flow<PurchaseUpdate>

    /**
     * The module's own diagnostics (JUL, FINE and up): among them every license notification
     * the Store sends, even one that changes nothing the app sees.
     */
    fun diagnostics(): Flow<String>
}

@ContributesBinding(AppScope::class)
@Inject
class NucleusPurchasesGateway : PurchasesGateway {
    // A broken nucleus_iap throws a LinkageError from here: it must make the probe report it,
    // not take the Lab down.
    override fun supported(): Availability = Availability.catching { InAppPurchase.isSupported }

    override suspend fun availability(): StoreAvailability = InAppPurchase.availability()

    override suspend fun appLicense(): AppLicense = InAppPurchase.appLicense()

    override suspend fun purchaseApp(hwnd: Long): PurchaseResult =
        InAppPurchase.purchaseApp { windows { ownerWindowHandle = hwnd.takeIf { it != 0L } } }

    override suspend fun products(ids: List<String>): List<StoreProduct> = InAppPurchase.products(ids)

    override suspend fun purchase(
        productId: String,
        hwnd: Long,
    ): PurchaseResult = InAppPurchase.purchase(productId) { windows { ownerWindowHandle = hwnd.takeIf { it != 0L } } }

    override suspend fun purchases(): List<Purchase> = InAppPurchase.purchases()

    override suspend fun restorePurchases(): List<Purchase> = InAppPurchase.restorePurchases()

    override suspend fun entitlement(productId: String): EntitlementStatus = InAppPurchase.entitlement(productId)

    override suspend fun consume(purchase: Purchase) {
        InAppPurchase.consume(purchase)
    }

    override fun appLicenseUpdates(): Flow<AppLicense> = InAppPurchase.appLicenseUpdates

    override fun purchaseUpdates(): Flow<PurchaseUpdate> = InAppPurchase.purchaseUpdates

    override fun diagnostics(): Flow<String> =
        callbackFlow {
            val handler =
                object : Handler() {
                    override fun publish(record: LogRecord) {
                        trySend(record.message)
                    }

                    override fun flush() = Unit

                    override fun close() = Unit
                }.apply { level = Level.FINE }
            iapLogger.level = Level.FINE
            iapLogger.addHandler(handler)
            awaitClose { iapLogger.removeHandler(handler) }
        }

    private companion object {
        // Held strongly: JUL keeps loggers weakly, and a collected one loses its level and handler.
        val iapLogger: Logger = Logger.getLogger("dev.nucleusframework.iap")
    }
}
