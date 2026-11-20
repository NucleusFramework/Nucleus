package dev.nucleusframework.lab.probes.system.macstore

import dev.nucleusframework.lab.core.Availability
import dev.nucleusframework.lab.core.mvi.CallOutcome
import dev.nucleusframework.store.mac.ProductType
import dev.nucleusframework.store.mac.Purchase
import dev.nucleusframework.store.mac.StoreEnvironment
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MacStoreContractTest {
    private fun purchase(
        type: ProductType = ProductType.Consumable,
        quantity: Int = 1,
        expires: Instant? = null,
    ) = Purchase(
        "coins",
        type,
        "42",
        "42",
        Instant.EPOCH,
        expires,
        null,
        quantity,
        StoreEnvironment.Xcode,
        null,
        "a.b.c",
    )

    @Test
    fun `product ids are trimmed, deduplicated and split on commas and spaces`() {
        assertEquals(
            listOf("pro", "coins", "premium.monthly"),
            parseProductIds(" pro,coins  pro,\npremium.monthly ,, "),
        )
        assertEquals(emptyList(), parseProductIds("  "))
    }

    @Test
    fun `a purchase line says what, which transaction and which store`() {
        assertEquals("Consumable · #42 · Xcode", purchase().summary())
        assertEquals("Consumable · #42 · Xcode · ×3", purchase(quantity = 3).summary())
        assertEquals(
            "AutoRenewableSubscription · #42 · Xcode · until 1970-01-01T00:00:01Z",
            purchase(ProductType.AutoRenewableSubscription, expires = Instant.ofEpochSecond(1)).summary(),
        )
    }

    @Test
    fun `switches start at StoreKit Testing's defaults and keep what the probe set`() {
        val initial = MacStoreState()
        assertTrue(initial.switches.getValue(StoreSwitch.Sheets))
        assertFalse(initial.switches.getValue(StoreSwitch.AskToBuy))
        val state =
            listOf(
                MacStoreEvent.Ready(Availability.Available, testing = true),
                MacStoreEvent.Switched(StoreSwitch.AskToBuy, true),
                MacStoreEvent.ActionProductChanged("pro"),
                MacStoreEvent.Called("testing.Refund(pro)", CallOutcome.Ok),
                MacStoreEvent.PurchasesListed(listOf(purchase())),
            ).fold(initial, MacStoreReducer::reduce)
        assertTrue(state.testing)
        assertTrue(state.switches.getValue(StoreSwitch.AskToBuy))
        assertTrue(state.switches.getValue(StoreSwitch.Sheets))
        assertEquals("pro", state.actionProductId)
        assertEquals(listOf("testing.Refund(pro)"), state.calls.map { it.call })
        assertEquals(listOf("coins"), state.purchases.map { it.productId })
    }
}
