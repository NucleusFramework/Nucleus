package dev.nucleusframework.iap

import dev.nucleusframework.core.runtime.Platform
import dev.nucleusframework.iap.internal.NativeIapBridge
import dev.nucleusframework.iap.internal.NativeStoreBackend
import dev.nucleusframework.iap.internal.StoreBackend
import dev.nucleusframework.iap.internal.UnsupportedStoreBackend
import kotlinx.coroutines.flow.Flow

/**
 * In-app purchases on the Microsoft Store (`Windows.Services.Store`). Other platforms are not
 * supported yet (macOS) or have no OS store (Linux): every call fails with
 * [StoreErrorCode.Unsupported] there, and [availability] says so up front.
 *
 * ```kotlin
 * val pro = InAppPurchase.products("pro").firstOrNull() ?: return
 * when (val result = InAppPurchase.purchase(pro)) {
 *     is PurchaseResult.Success -> unlockPro()
 *     PurchaseResult.Pending -> showAwaitingApproval()
 *     PurchaseResult.Cancelled -> Unit
 *     is PurchaseResult.Failed -> showError(result.error)
 * }
 * if (InAppPurchase.entitlement("pro").isActive) unlockPro()
 * ```
 *
 * Product ids are the developer-defined ids — Partner Center's add-on *product id*
 * (`InAppOfferToken`), not its `StoreId`. Every call suspends without
 * blocking a thread, may be called from any thread, and cancelling the coroutine cancels the
 * underlying store operation.
 *
 * On Windows the Store only serves apps with package identity (an MSIX/AppX install); an
 * NSIS/MSI/portable build reports [StoreAvailability.NotPackaged].
 */
@Suppress("TooManyFunctions") // The module's whole facade, one entry point per store operation.
public object InAppPurchase {
    @Volatile
    private var override: StoreBackend? = null

    private val default: StoreBackend by lazy {
        when (Platform.Current) {
            Platform.Windows ->
                if (NativeIapBridge.isAvailable) {
                    NativeStoreBackend(NativeIapBridge)
                } else {
                    UnsupportedStoreBackend("The in-app purchase native bridge failed to load")
                }
            else -> UnsupportedStoreBackend("In-app purchases are not available on ${Platform.Current}")
        }
    }

    internal val backend: StoreBackend
        get() = override ?: default

    internal fun setBackendForTesting(backend: StoreBackend?) {
        override = backend
    }

    /** `true` on a platform with a store backend that loaded. [availability] says more. */
    public val isSupported: Boolean
        get() = backend.isSupported

    /** Whether purchases can work in this process right now. */
    public suspend fun availability(): StoreAvailability = backend.availability()

    /**
     * The products among [ids] the store sells to this customer, in the order asked for.
     * Unknown ids are left out; blank and repeated ids are ignored.
     *
     * @throws StoreException when the store cannot be queried
     */
    public suspend fun products(ids: Collection<String>): List<StoreProduct> = backend.products(ids.toList())

    /** [products] for a few ids. */
    public suspend fun products(vararg ids: String): List<StoreProduct> = backend.products(ids.toList())

    /** The product [id], or `null` when the store does not sell it. */
    public suspend fun product(id: String): StoreProduct? = backend.products(listOf(id)).firstOrNull()

    /**
     * Shows the store's purchase sheet for [product] and waits for the customer.
     *
     * Store failures come back as [PurchaseResult.Failed], never as an exception. Non-consumables
     * and subscriptions are finished by the store at once; a consumable stays pending in
     * [purchases] until [consume] reports it delivered.
     *
     * A non-consumable or subscription this device already licenses returns
     * `Success(alreadyOwned = true)` without showing anything: the Microsoft Store was seen
     * offering an owned product for sale again. On a device whose licenses have not synced yet
     * the Store still decides, so hide the buy button while [entitlement] is active.
     */
    public suspend fun purchase(
        product: StoreProduct,
        options: PurchaseOptions.() -> Unit = {},
    ): PurchaseResult = purchase(product.id, options)

    /** [purchase] by product id, without listing the product first. */
    public suspend fun purchase(
        productId: String,
        options: PurchaseOptions.() -> Unit = {},
    ): PurchaseResult = backend.purchase(productId, PurchaseOptions().apply(options))

    /**
     * What the customer currently owns: active non-consumables and subscriptions, plus
     * consumables bought but not yet [consume]d. Silent — never shows UI; offline on Windows.
     *
     * @throws StoreException when the store cannot be queried
     */
    public suspend fun purchases(): List<Purchase> = backend.purchases()

    /**
     * [purchases] after syncing with the store, for a "Restore purchases" button. The Microsoft
     * Store keeps licenses in sync itself, so it currently equals [purchases].
     *
     * @throws StoreException when the store cannot be reached
     */
    public suspend fun restorePurchases(): List<Purchase> = backend.restorePurchases()

    /**
     * Whether the customer is entitled to [productId] now. Reads the local store state, so it
     * is cheap and works offline — call it at launch to gate features.
     *
     * @throws StoreException when the store cannot be queried
     */
    public suspend fun entitlement(productId: String): EntitlementStatus = backend.entitlement(productId)

    /**
     * Reports a consumable delivered, so the customer can buy it again. Call it only after the
     * content is safely granted: an unconsumed purchase is listed again by [purchases] at next
     * launch, which is what makes delivery crash-safe. Retrying with the same [purchase] is
     * idempotent on the Microsoft Store (the tracking id is fixed per purchase).
     *
     * @param quantity units to consume; Store-managed consumables on Windows can hold several
     * @throws StoreException on a non-consumable, a purchase without a consume token, or a store error
     */
    public suspend fun consume(
        purchase: Purchase,
        quantity: Int = 1,
    ) {
        backend.consume(purchase, quantity)
    }

    /**
     * The license of the app itself: bought, in trial, or neither. Reads the local license, so
     * it is cheap and works offline — call it at launch to decide what to unlock.
     *
     * The Store does not end a trial for you: once [AppLicense.isActive] is `false`, lock what
     * the trial gave. To sell an app with a trial, give it a price and a free trial in Partner
     * Center; [purchaseApp] then converts the trial.
     *
     * @throws StoreException when the store cannot be queried
     */
    public suspend fun appLicense(): AppLicense = backend.appLicense()

    /**
     * Shows the Store's purchase dialog for the app itself — the "Buy the full version" of a
     * trial. On success the [PurchaseResult.Success] purchase's `productId` is the app's
     * `StoreId`; read [appLicense] again for the new license.
     *
     * When this device already holds the full license, returns `Success(alreadyOwned = true)`
     * without showing anything, as [purchase] does for an owned add-on.
     *
     * Store failures come back as [PurchaseResult.Failed], never as an exception.
     */
    public suspend fun purchaseApp(options: PurchaseOptions.() -> Unit = {}): PurchaseResult =
        backend.purchaseApp(PurchaseOptions().apply(options))

    /**
     * Changes of the app's own [AppLicense]: a trial converted to a full license, a license
     * granted or revoked from outside the app. Trial time running down is not reported; schedule
     * on [AppLicense.trialTimeRemaining] or re-read [appLicense] to notice the end of a trial.
     *
     * Empty on unsupported platforms.
     */
    public val appLicenseUpdates: Flow<AppLicense>
        get() = backend.appLicenseUpdates

    /**
     * Purchase changes the app did not start: renewals, refunds and purchases made elsewhere.
     * Collect it for the life of the app, from launch. Purchases made through [purchase] are not repeated here.
     *
     * Empty on unsupported platforms.
     */
    public val purchaseUpdates: Flow<PurchaseUpdate>
        get() = backend.updates
}
