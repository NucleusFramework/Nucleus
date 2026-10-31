package dev.nucleusframework.store.microsoft.internal

import dev.nucleusframework.store.microsoft.ProductType
import dev.nucleusframework.store.microsoft.Purchase
import dev.nucleusframework.store.microsoft.PurchaseState
import dev.nucleusframework.store.microsoft.PurchaseUpdate
import java.time.Instant

/**
 * Turns Microsoft Store license snapshots into [PurchaseUpdate]s.
 *
 * `OfflineLicensesChanged` says *that* licenses changed, not what: every event carries the full
 * list of active add-on licenses and this diffs it against the previous one. A product that
 * appears (or whose expiration moves later) is [PurchaseUpdate.Purchased], one that disappears
 * is [PurchaseUpdate.Revoked]. Purchases made through [dev.nucleusframework.store.microsoft.MicrosoftStorePurchases.purchase]
 * are reported by that call, as StoreKit's `Transaction.updates` does, so a product with a
 * purchase in flight is absorbed silently. Consumables are not licenses and never tracked.
 */
internal class LicenseTracker(
    private val now: () -> Instant = Instant::now,
) {
    private var known: Map<String, Purchase>? = null
    private val inFlight = HashMap<String, Int>()

    /** The first known state; ignored once a snapshot already established one. */
    @Synchronized
    fun seed(purchases: List<Purchase>) {
        if (known == null) known = index(purchases, emptyMap())
    }

    @Synchronized
    fun beginPurchase(productId: String) {
        inFlight.merge(productId, 1, Int::plus)
    }

    @Synchronized
    fun endPurchase(productId: String) {
        inFlight.computeIfPresent(productId) { _, count -> (count - 1).takeIf { it > 0 } }
    }

    /** Records a purchase the app already reported, so the license it creates is not news. */
    @Synchronized
    fun recordPurchase(purchase: Purchase) {
        val current = known ?: return
        if (purchase.productType == ProductType.Consumable) return
        known = current + (purchase.productId to purchase)
    }

    @Synchronized
    fun onSnapshot(snapshot: List<Purchase>): List<PurchaseUpdate> {
        val previous = known
        val current = index(snapshot, previous.orEmpty())
        known = current
        if (previous == null) return emptyList()

        val updates = mutableListOf<PurchaseUpdate>()
        for ((id, purchase) in current) {
            if (id in inFlight) continue
            val before = previous[id]
            val renewed =
                before != null &&
                    purchase.expirationTime != null &&
                    (before.expirationTime == null || purchase.expirationTime > before.expirationTime)
            if (before == null || renewed) updates += PurchaseUpdate.Purchased(purchase)
        }
        for ((id, before) in previous) {
            if (id in current || id in inFlight) continue
            val lapsed = before.expirationTime?.let { !it.isAfter(now()) } ?: false
            updates +=
                PurchaseUpdate.Revoked(
                    before.copy(state = if (lapsed) PurchaseState.Expired else PurchaseState.Revoked),
                )
        }
        return updates
    }

    /** One entry per product (the latest expiration wins), keeping the type learnt earlier. */
    private fun index(
        purchases: List<Purchase>,
        previous: Map<String, Purchase>,
    ): Map<String, Purchase> {
        val out = LinkedHashMap<String, Purchase>()
        for (raw in purchases) {
            if (raw.productType == ProductType.Consumable || raw.state != PurchaseState.Purchased) continue
            val typed =
                previous[raw.productId]
                    ?.productType
                    ?.takeIf { it != ProductType.Unknown && it != raw.productType }
                    ?.let { raw.copy(productType = it) }
                    ?: raw
            val existing = out[raw.productId]
            if (existing == null || (typed.expirationTime ?: Instant.MAX) > (existing.expirationTime ?: Instant.MAX)) {
                out[raw.productId] = typed
            }
        }
        return out
    }
}
