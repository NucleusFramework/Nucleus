package dev.nucleusframework.store.microsoft.internal

import dev.nucleusframework.store.microsoft.ProductType
import dev.nucleusframework.store.microsoft.Purchase
import dev.nucleusframework.store.microsoft.PurchaseState
import dev.nucleusframework.store.microsoft.PurchaseUpdate
import java.time.Instant
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class LicenseTrackerTest {
    private val now = Instant.parse("2026-09-25T12:00:00Z")
    private val tracker = LicenseTracker { now }

    private fun license(
        id: String,
        expires: Instant? = null,
        type: ProductType = ProductType.NonConsumable,
        state: PurchaseState = PurchaseState.Purchased,
    ) = Purchase(id, type, null, null, null, expires, null, 1, null, state, null, null)

    @Test
    fun `the first snapshot is a baseline, not news`() {
        assertEquals(emptyList(), tracker.onSnapshot(listOf(license("a"), license("b"))))
        assertEquals(emptyList(), tracker.onSnapshot(listOf(license("a"), license("b"))))
    }

    @Test
    fun `a seed after a snapshot does not reset the state`() {
        tracker.onSnapshot(listOf(license("a")))
        tracker.seed(emptyList())
        assertEquals(emptyList(), tracker.onSnapshot(listOf(license("a"))))
    }

    @Test
    fun `new, renewed, revoked and expired`() {
        val month = now.plusSeconds(30L * 86_400)
        tracker.seed(
            listOf(
                license("sub", expires = now.plusSeconds(60)),
                license("gone"),
                license("lapsed", expires = now.minusSeconds(1)),
            ),
        )
        val updates = tracker.onSnapshot(listOf(license("sub", expires = month), license("fresh")))
        val byId = updates.associateBy { it.purchase.productId }
        assertIs<PurchaseUpdate.Purchased>(byId["sub"])
        assertIs<PurchaseUpdate.Purchased>(byId["fresh"])
        assertEquals(PurchaseState.Revoked, assertIs<PurchaseUpdate.Revoked>(byId["gone"]).purchase.state)
        assertEquals(PurchaseState.Expired, assertIs<PurchaseUpdate.Revoked>(byId["lapsed"]).purchase.state)
        assertEquals(4, updates.size)
    }

    @Test
    fun `an expiration moving earlier is not a renewal`() {
        tracker.seed(listOf(license("sub", expires = now.plusSeconds(3_600))))
        assertEquals(emptyList(), tracker.onSnapshot(listOf(license("sub", expires = now.plusSeconds(60)))))
    }

    @Test
    fun `consumables and inactive licenses are never tracked`() {
        tracker.seed(emptyList())
        val updates =
            tracker.onSnapshot(
                listOf(license("coins", type = ProductType.Consumable), license("dead", state = PurchaseState.Expired)),
            )
        assertEquals(emptyList(), updates)
    }

    @Test
    fun `duplicate licenses for one product collapse to the latest expiration`() {
        tracker.seed(emptyList())
        val later = now.plusSeconds(999)
        val updates =
            tracker.onSnapshot(
                listOf(license("sub", expires = now.plusSeconds(1)), license("sub", expires = later)),
            )
        assertEquals(later, updates.single().purchase.expirationTime)
    }

    @Test
    fun `a purchase in flight absorbs its own license`() {
        tracker.seed(emptyList())
        tracker.beginPurchase("pro")
        assertEquals(emptyList(), tracker.onSnapshot(listOf(license("pro"))))
        tracker.recordPurchase(license("pro"))
        tracker.endPurchase("pro")
        assertEquals(emptyList(), tracker.onSnapshot(listOf(license("pro"))))
    }

    @Test
    fun `overlapping purchases of one product keep it in flight until the last ends`() {
        tracker.seed(emptyList())
        tracker.beginPurchase("pro")
        tracker.beginPurchase("pro")
        tracker.endPurchase("pro")
        assertEquals(emptyList(), tracker.onSnapshot(listOf(license("pro"))))
        tracker.endPurchase("pro")
        tracker.endPurchase("pro")
        assertIs<PurchaseUpdate.Revoked>(tracker.onSnapshot(emptyList()).single())
    }

    @Test
    fun `the type learnt earlier survives a snapshot that guesses it`() {
        tracker.seed(listOf(license("pro", type = ProductType.NonConsumable)))
        // The license stream only guesses types; a guess must not overwrite what purchases said.
        val renewal =
            tracker.onSnapshot(
                listOf(license("pro", expires = now.plusSeconds(10), type = ProductType.AutoRenewableSubscription)),
            )
        assertEquals(ProductType.NonConsumable, renewal.single().purchase.productType)
    }

    /**
     * Model check: over random snapshot sequences, every product that appears, disappears or
     * renews outside a purchase produces exactly one matching update, and nothing else does.
     */
    @Test
    fun `random snapshot sequences match the model`() {
        val random = Random(566)
        repeat(2_000) { round ->
            val tracker = LicenseTracker { now }
            val ids = (0 until 6).map { "p$it" }
            var model: Map<String, Instant?>? = null
            val inFlight = mutableSetOf<String>()
            repeat(30) {
                if (random.nextInt(4) == 0) {
                    val id = ids.random(random)
                    if (id in inFlight) {
                        tracker.endPurchase(id)
                        inFlight -= id
                    } else {
                        tracker.beginPurchase(id)
                        inFlight += id
                    }
                }
                val snapshot =
                    ids
                        .filter { random.nextBoolean() }
                        .associateWith { if (random.nextBoolean()) null else now.plusSeconds(random.nextLong(-50, 50)) }
                val updates = tracker.onSnapshot(snapshot.map { (id, exp) -> license(id, expires = exp) })

                val previous = model
                model = snapshot
                if (previous == null) {
                    assertTrue(updates.isEmpty(), "round $round: baseline produced $updates")
                    return@repeat
                }
                val expected = mutableSetOf<Pair<String, Boolean>>()
                for ((id, exp) in snapshot) {
                    if (id in inFlight) continue
                    val before = previous[id]
                    val appeared = id !in previous
                    val renewed = !appeared && exp != null && (before == null || exp > before)
                    if (appeared || renewed) expected += id to true
                }
                for (id in previous.keys - snapshot.keys) if (id !in inFlight) expected += id to false
                val actual = updates.map { it.purchase.productId to (it is PurchaseUpdate.Purchased) }
                assertEquals(actual.size, actual.toSet().size, "round $round: duplicate updates $actual")
                assertEquals(expected, actual.toSet(), "round $round")
            }
        }
    }
}
