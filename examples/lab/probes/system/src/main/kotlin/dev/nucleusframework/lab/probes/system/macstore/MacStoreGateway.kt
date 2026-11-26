package dev.nucleusframework.lab.probes.system.macstore

import dev.nucleusframework.lab.core.Availability
import dev.nucleusframework.store.mac.AppLicense
import dev.nucleusframework.store.mac.AppTransactionInfo
import dev.nucleusframework.store.mac.EntitlementStatus
import dev.nucleusframework.store.mac.MacAppStore
import dev.nucleusframework.store.mac.Purchase
import dev.nucleusframework.store.mac.PurchaseResult
import dev.nucleusframework.store.mac.PurchaseUpdate
import dev.nucleusframework.store.mac.StoreAvailability
import dev.nucleusframework.store.mac.StoreKitTestingSettings
import dev.nucleusframework.store.mac.StoreProduct
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.flow.Flow

/** Port over `mac-app-store`. Store failures surface as `StoreException`, as the module throws them. */
interface MacStoreGateway {
    fun supported(): Availability

    fun testing(): Boolean

    suspend fun availability(): StoreAvailability

    suspend fun products(ids: List<String>): List<StoreProduct>

    suspend fun purchase(
        productId: String,
        window: Long,
    ): PurchaseResult

    suspend fun purchases(): List<Purchase>

    suspend fun entitlement(productId: String): EntitlementStatus

    suspend fun consume(purchase: Purchase)

    suspend fun appTransaction(): AppTransactionInfo

    suspend fun appLicense(): AppLicense

    suspend fun openStorePage()

    fun purchaseUpdates(): Flow<PurchaseUpdate>

    suspend fun settings(): StoreKitTestingSettings

    suspend fun switch(
        switch: StoreSwitch,
        enabled: Boolean,
    )

    suspend fun act(
        action: StoreAction,
        productId: String,
    )

    suspend fun clearTransactions()
}

@ContributesBinding(AppScope::class)
@Inject
class NucleusMacStoreGateway : MacStoreGateway {
    // A broken nucleus_macstore throws a LinkageError from here: it must make the probe report
    // it, not take the Lab down.
    override fun supported(): Availability = Availability.catching { MacAppStore.isSupported }

    override fun testing(): Boolean = runCatching { MacAppStore.testing.isActive }.getOrDefault(false)

    override suspend fun availability(): StoreAvailability = MacAppStore.availability()

    override suspend fun products(ids: List<String>): List<StoreProduct> = MacAppStore.purchases.products(ids)

    override suspend fun purchase(
        productId: String,
        window: Long,
    ): PurchaseResult = MacAppStore.purchases.purchase(productId) { this.window = window.takeIf { it != 0L } }

    override suspend fun purchases(): List<Purchase> = MacAppStore.purchases.list()

    override suspend fun entitlement(productId: String): EntitlementStatus =
        MacAppStore.purchases.entitlement(productId)

    override suspend fun consume(purchase: Purchase) {
        MacAppStore.purchases.consume(purchase)
    }

    override suspend fun appTransaction(): AppTransactionInfo = MacAppStore.appTransaction()

    override suspend fun appLicense(): AppLicense = MacAppStore.appLicense()

    override suspend fun openStorePage() {
        MacAppStore.openStorePage()
    }

    override fun purchaseUpdates(): Flow<PurchaseUpdate> = MacAppStore.purchases.purchaseUpdates

    override suspend fun settings(): StoreKitTestingSettings = MacAppStore.testing.settings()

    override suspend fun switch(
        switch: StoreSwitch,
        enabled: Boolean,
    ) {
        val testing = MacAppStore.testing
        when (switch) {
            StoreSwitch.Sheets -> testing.setPurchaseSheetsEnabled(enabled)
            StoreSwitch.AskToBuy -> testing.setAskToBuyEnabled(enabled)
            StoreSwitch.BillingRetry -> testing.setBillingRetryOnRenewal(enabled)
            StoreSwitch.GracePeriod -> testing.setBillingGracePeriod(enabled)
        }
    }

    override suspend fun act(
        action: StoreAction,
        productId: String,
    ) {
        val testing = MacAppStore.testing
        when (action) {
            StoreAction.Refund -> testing.refund(productId)
            StoreAction.ForceRenewal -> testing.forceRenewal(productId)
            StoreAction.Expire -> testing.expireSubscription(productId)
            StoreAction.BuyOutside -> testing.buyOutsideApp(productId)
            StoreAction.ApproveAskToBuy -> testing.approveAskToBuy(productId)
            StoreAction.ResolveBillingIssue -> testing.resolveBillingIssue(productId)
        }
    }

    override suspend fun clearTransactions() {
        MacAppStore.testing.clearTransactions()
    }
}
