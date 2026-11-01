package dev.nucleusframework.store.mac

import dev.nucleusframework.store.mac.internal.DevConfiguration
import dev.nucleusframework.store.mac.internal.FakeTransport
import dev.nucleusframework.store.mac.internal.NativeStoreBackend
import dev.nucleusframework.store.mac.internal.ok
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MacAppStoreTestingTest {
    @AfterTest
    fun restore() {
        MacAppStore.setBackendForTesting(null)
    }

    private fun install(dev: DevConfiguration?): FakeTransport {
        val transport = FakeTransport { ok("{}") }
        MacAppStore.setBackendForTesting(NativeStoreBackend(transport, dev))
        return transport
    }

    @Test
    fun `without a StoreKit configuration every control refuses`() =
        runBlocking {
            val transport = install(dev = null)
            assertFalse(MacAppStore.testing.isActive)
            val error = assertFailsWith<StoreException> { MacAppStore.testing.refund("pro") }
            assertEquals(StoreErrorCode.InvalidArgument, error.code)
            assertTrue(error.message!!.contains("nucleus.macAppStore.storeKitConfiguration"))
            assertTrue(transport.requests.isEmpty())
        }

    @Test
    fun `controls reach the session as settings and actions`() =
        runBlocking {
            val transport = install(DevConfiguration("/p.storekit", "/x/StoreKitTest"))
            val testing = MacAppStore.testing
            assertTrue(testing.isActive)
            testing.setPurchaseSheetsEnabled(false)
            testing.setRenewalRate(RenewalRate.OneRenewalEveryTenSeconds)
            testing.setStorefront("FRA")
            testing.forceRenewal("premium.monthly")
            testing.setAutoRenew("premium.monthly", enabled = false)

            val sent = transport.requests.filter { it.method != "dev.configure" }
            assertEquals(listOf("dev.set", "dev.set", "dev.set", "dev.act", "dev.act"), sent.map { it.method })
            assertEquals("dialogs", sent[0].args["key"]!!.jsonPrimitive.content)
            assertEquals("false", sent[0].args["bool"]!!.jsonPrimitive.content)
            assertEquals("1004", sent[1].args["int"]!!.jsonPrimitive.content)
            assertEquals("FRA", sent[2].args["string"]!!.jsonPrimitive.content)
            assertEquals("forceRenewal", sent[3].args["action"]!!.jsonPrimitive.content)
            assertEquals("premium.monthly", sent[3].args["productId"]!!.jsonPrimitive.content)
            assertEquals("disableAutoRenew", sent[4].args["action"]!!.jsonPrimitive.content)
        }

    @Test
    fun `settings are read back from the session`() =
        runBlocking {
            val transport =
                FakeTransport { request ->
                    if (request.method == "dev.settings") {
                        ok(
                            """{"dialogs":false,"askToBuy":true,"billingRetryOnRenewal":true,"billingGracePeriod":false,
                               "interruptedPurchases":false,"timeRate":1004,"storefront":"FRA"}""",
                        )
                    } else {
                        ok("{}")
                    }
                }
            MacAppStore.setBackendForTesting(
                NativeStoreBackend(transport, DevConfiguration("/p.storekit", "/x/StoreKitTest")),
            )
            val settings = MacAppStore.testing.settings()
            assertEquals(
                StoreKitTestingSettings(
                    purchaseSheetsEnabled = false,
                    askToBuyEnabled = true,
                    billingRetryOnRenewal = true,
                    billingGracePeriod = false,
                    interruptedPurchasesEnabled = false,
                    renewalRate = RenewalRate.OneRenewalEveryTenSeconds,
                    storefront = "FRA",
                ),
                settings,
            )
        }

    @Test
    fun `a blank product id fails before the bridge`() =
        runBlocking {
            val transport = install(DevConfiguration("/p.storekit", "/x/StoreKitTest"))
            assertFailsWith<StoreException> { MacAppStore.testing.refund(" ") }
            assertTrue(transport.requests.none { it.method == "dev.act" })
        }
}
