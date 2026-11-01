package dev.nucleusframework.store.mac

/**
 * In-app purchases on the Mac App Store. Reached through [MacAppStore.purchases].
 *
 * Product ids are the App Store Connect product ids. Every call suspends without blocking a
 * thread, may be called from any thread, and cancelling the coroutine cancels the underlying
 * StoreKit operation.
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
}
