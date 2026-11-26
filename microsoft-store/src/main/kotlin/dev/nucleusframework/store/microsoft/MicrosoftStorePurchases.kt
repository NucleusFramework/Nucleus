package dev.nucleusframework.store.microsoft

import kotlinx.coroutines.flow.Flow

/**
 * In-app purchases on the Microsoft Store: add-ons, and the app's own license and trial.
 * Reached through [MicrosoftStore.purchases].
 *
 * ```kotlin
 * val purchases = MicrosoftStore.purchases
 * val pro = purchases.product("pro") ?: return
 * when (val result = purchases.purchase(pro)) {
 *     is PurchaseResult.Success -> unlockPro()
 *     PurchaseResult.Pending -> showAwaitingApproval()
 *     PurchaseResult.Cancelled -> Unit
 *     is PurchaseResult.Failed -> showError(result.error)
 * }
 * if (purchases.entitlement("pro").isActive) unlockPro()
 * ```
 *
 * Product ids are the developer-defined ids — Partner Center's add-on *product id*
 * (`InAppOfferToken`), not its `StoreId`. Every call suspends without
 * blocking a thread, may be called from any thread, and cancelling the coroutine cancels the
 * underlying store operation.
 */
@Suppress("TooManyFunctions") // One entry point per store operation.
public class MicrosoftStorePurchases internal constructor() {
    private val backend
        get() = MicrosoftStore.backend

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
     * [list] until [consume] reports it delivered.
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
    public suspend fun list(): List<Purchase> = backend.purchases()

    /**
     * [list] after syncing with the store, for a "Restore purchases" button. The Microsoft
     * Store keeps licenses in sync itself, so it currently equals [list].
     *
     * @throws StoreException when the store cannot be reached
     */
    public suspend fun restore(): List<Purchase> = backend.restorePurchases()

    /**
     * Whether the customer is entitled to [productId] now. Reads the local store state, so it
     * is cheap and works offline — call it at launch to gate features.
     *
     * @throws StoreException when the store cannot be queried
     */
    public suspend fun entitlement(productId: String): EntitlementStatus = backend.entitlement(productId)

    /**
     * Reports a consumable delivered, so the customer can buy it again. Call it only after the
     * content is safely granted: an unconsumed purchase is listed again by [list] at next
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
