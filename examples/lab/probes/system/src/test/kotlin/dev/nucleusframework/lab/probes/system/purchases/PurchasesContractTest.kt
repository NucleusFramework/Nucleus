package dev.nucleusframework.lab.probes.system.purchases

import dev.nucleusframework.lab.core.mvi.CallOutcome
import dev.nucleusframework.store.microsoft.AppLicense
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals

class PurchasesContractTest {
    private fun license(
        active: Boolean = true,
        trial: Boolean = false,
        sku: String? = "9NAPP/0010",
        left: Duration? = null,
    ) = AppLicense(active, trial, null, left, false, null, sku)

    @Test
    fun `product ids are trimmed, deduplicated and split on commas and spaces`() {
        assertEquals(listOf("pro", "coins", "sub"), parseProductIds(" pro,coins  pro,\nsub ,, "))
        assertEquals(emptyList(), parseProductIds("  "))
    }

    @Test
    fun `an install the Store did not license never reads as bought`() {
        assertEquals("full license", license().summary())
        assertEquals("not licensed by the Store (active=true)", license(sku = null).summary())
        assertEquals("trial, 48 h left", license(trial = true, left = Duration.ofDays(2)).summary())
        assertEquals("trial, unlimited", license(trial = true).summary())
        assertEquals("trial over", license(active = false, trial = true).summary())
    }

    @Test
    fun `calls and listings reduce without touching each other`() {
        val state =
            listOf(
                PurchasesEvent.ProductIdsChanged("pro"),
                PurchasesEvent.Called("products", CallOutcome.Ok, "[]"),
                PurchasesEvent.LicenseRead(license(sku = null)),
                PurchasesEvent.Called("purchases", CallOutcome(false, "NotPackaged")),
            ).fold(PurchasesState(), PurchasesReducer::reduce)
        assertEquals("pro", state.productIds)
        assertEquals(listOf("products", "purchases"), state.calls.map { it.call })
        assertEquals(null, state.license?.skuStoreId)
        assertEquals(emptyList(), state.products)
    }
}
