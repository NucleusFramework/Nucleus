package dev.nucleusframework.lab.probes.system.purchases

import dev.nucleusframework.lab.core.Availability
import dev.nucleusframework.store.microsoft.AppLicense
import dev.nucleusframework.store.microsoft.EntitlementStatus
import dev.nucleusframework.store.microsoft.MicrosoftStore
import dev.nucleusframework.store.microsoft.Purchase
import dev.nucleusframework.store.microsoft.PurchaseResult
import dev.nucleusframework.store.microsoft.PurchaseUpdate
import dev.nucleusframework.store.microsoft.StoreAvailability
import dev.nucleusframework.store.microsoft.StoreProduct
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

/** Port over `microsoft-store`. Store failures surface as `StoreException`, as the module throws them. */
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
    // A broken nucleus_msstore throws a LinkageError from here: it must make the probe report it,
    // not take the Lab down.
    override fun supported(): Availability = Availability.catching { MicrosoftStore.isSupported }

    override suspend fun availability(): StoreAvailability = MicrosoftStore.availability()

    override suspend fun appLicense(): AppLicense = MicrosoftStore.purchases.appLicense()

    override suspend fun purchaseApp(hwnd: Long): PurchaseResult =
        MicrosoftStore.purchases.purchaseApp { windows { ownerWindowHandle = hwnd.takeIf { it != 0L } } }

    override suspend fun products(ids: List<String>): List<StoreProduct> = MicrosoftStore.purchases.products(ids)

    override suspend fun purchase(
        productId: String,
        hwnd: Long,
    ): PurchaseResult =
        MicrosoftStore.purchases.purchase(productId) {
            windows {
                ownerWindowHandle =
                    hwnd.takeIf { it != 0L }
            }
        }

    override suspend fun purchases(): List<Purchase> = MicrosoftStore.purchases.list()

    override suspend fun restorePurchases(): List<Purchase> = MicrosoftStore.purchases.restore()

    override suspend fun entitlement(productId: String): EntitlementStatus =
        MicrosoftStore.purchases.entitlement(productId)

    override suspend fun consume(purchase: Purchase) {
        MicrosoftStore.purchases.consume(purchase)
    }

    override fun appLicenseUpdates(): Flow<AppLicense> = MicrosoftStore.purchases.appLicenseUpdates

    override fun purchaseUpdates(): Flow<PurchaseUpdate> = MicrosoftStore.purchases.purchaseUpdates

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
        val iapLogger: Logger = Logger.getLogger("dev.nucleusframework.store.microsoft")
    }
}
