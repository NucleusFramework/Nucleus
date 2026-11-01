package dev.nucleusframework.store.mac

/**
 * In-app purchases on the Mac App Store. Reached through [MacAppStore.purchases].
 *
 * ```kotlin
 * val purchases = MacAppStore.purchases
 * if (purchases.entitlement("pro").isActive) unlockPro()
 *
 * when (val result = purchases.purchase("pro") { window = taoWindow.nativeHandle }) {
 *     is PurchaseResult.Success -> unlockPro()
 *     PurchaseResult.Pending -> showAwaitingApproval()
 *     PurchaseResult.Cancelled -> Unit
 *     is PurchaseResult.Failed -> showError(result.error)
 * }
 * ```
 *
 * Product ids are the App Store Connect product ids. Every call suspends without blocking a
 * thread, may be called from any thread, and cancelling the coroutine cancels the underlying
 * StoreKit operation — except the purchase sheet (see [purchase]). Only transactions StoreKit
 * verified are reported.
 */
public class MacAppStorePurchases internal constructor() {
    private val backend
        get() = MacAppStore.backend

    /**
     * The products among [ids] the App Store sells to this customer, in the order asked for.
     * Unknown ids are left out; blank and repeated ids are ignored.
     *
     * @throws StoreException when the store cannot be queried
     */
    public suspend fun products(ids: Collection<String>): List<StoreProduct> = backend.products(ids.toList())

    /** [products] for a few ids. */
    public suspend fun products(vararg ids: String): List<StoreProduct> = backend.products(ids.toList())

    /** The product [id], or `null` when the App Store does not sell it. */
    public suspend fun product(id: String): StoreProduct? = backend.products(listOf(id)).firstOrNull()

    /**
     * Shows the App Store purchase sheet for [product] and waits for the customer.
     *
     * Store failures come back as [PurchaseResult.Failed], never as an exception. Non-consumables
     * and subscriptions are finished at once; a consumable stays in [list] until [consume]
     * reports it delivered. The sheet needs the app's event loop running (a Nucleus window).
     *
     * Cancelling the coroutine returns at once but does not close Apple's sheet: StoreKit has no
     * way to, and closing it from the app leaves StoreKit stuck. The customer still answers it; a
     * purchase made there is recorded and shows up in [entitlement] and [list].
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
     * What the customer owns now: active non-consumables and subscriptions, plus consumables
     * bought but not yet [consume]d. Silent — never shows UI.
     *
     * @throws StoreException when the store cannot be queried
     */
    public suspend fun list(): List<Purchase> = backend.purchases()

    /**
     * Whether the customer is entitled to [productId] now, and if not, why (expired, revoked,
     * in billing retry). Call it at launch to gate features.
     *
     * @throws StoreException when the store cannot be queried
     */
    public suspend fun entitlement(productId: String): EntitlementStatus = backend.entitlement(productId)

    /**
     * Reports a consumable delivered, so StoreKit stops handing it back and the customer can buy
     * it again. Call it only once the content is safely granted: an unconsumed purchase is listed
     * again by [list] at the next launch, which is what makes delivery crash-safe. Consuming twice
     * does nothing the second time.
     *
     * @throws StoreException on a product that is not a consumable, or a store error
     */
    public suspend fun consume(purchase: Purchase) {
        backend.consume(purchase)
    }
}
