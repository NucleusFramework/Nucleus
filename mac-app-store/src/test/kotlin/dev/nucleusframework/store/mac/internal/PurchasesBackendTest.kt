package dev.nucleusframework.store.mac.internal

import dev.nucleusframework.store.mac.EntitlementState
import dev.nucleusframework.store.mac.ProductType
import dev.nucleusframework.store.mac.Purchase
import dev.nucleusframework.store.mac.PurchaseOptions
import dev.nucleusframework.store.mac.PurchaseResult
import dev.nucleusframework.store.mac.StoreEnvironment
import dev.nucleusframework.store.mac.StoreErrorCode
import dev.nucleusframework.store.mac.StoreException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PurchasesBackendTest {
    private val token = UUID.fromString("6f1c2a3b-0000-4000-8000-000000000001")

    private fun transaction(
        productId: String = "pro",
        type: String = "nonConsumable",
        id: String = "7",
        extra: String = "",
    ) = """{"productId":"$productId","productType":"$type","transactionId":"$id","originalTransactionId":"3",
           "purchaseTime":1700000000000,"quantity":1,"environment":"xcode","jws":"eyJ.sig"$extra}"""

    private fun options(block: PurchaseOptions.() -> Unit = {}) = PurchaseOptions().apply(block)

    @Test
    fun `a successful purchase maps the verified transaction`() =
        runBlocking {
            val transport =
                FakeTransport {
                    ok(
                        """{"status":"success","purchase":${transaction(extra = ""","appAccountToken":"$token"""")}}""",
                    )
                }
            val result = NativeStoreBackend(transport).purchase("pro", options { appAccountToken = token })
            val purchase = assertIs<PurchaseResult.Success>(result).purchase
            assertEquals("pro", purchase.productId)
            assertEquals(ProductType.NonConsumable, purchase.productType)
            assertEquals("7", purchase.transactionId)
            assertEquals("3", purchase.originalTransactionId)
            assertEquals(Instant.ofEpochMilli(1700000000000), purchase.purchaseTime)
            assertEquals(StoreEnvironment.Xcode, purchase.environment)
            assertEquals(token, purchase.appAccountToken)
            assertEquals("eyJ.sig", purchase.signedTransaction)
            assertNull(purchase.expirationTime)
        }

    @Test
    fun `purchase options travel to the bridge`() =
        runBlocking {
            val transport = FakeTransport { ok("""{"status":"cancelled"}""") }
            NativeStoreBackend(transport).purchase(
                "coins",
                options {
                    window = 0x7f00L
                    quantity = 3
                    appAccountToken = token
                },
            )
            val args = transport.requests.single().args
            assertEquals("coins", args["productId"]!!.jsonPrimitive.content)
            assertEquals(0x7f00L.toString(), args["window"]!!.jsonPrimitive.content)
            assertEquals("3", args["quantity"]!!.jsonPrimitive.content)
            assertEquals(token.toString(), args["appAccountToken"]!!.jsonPrimitive.content)
        }

    @Test
    fun `pending and cancelled are results, not failures`() =
        runBlocking {
            assertEquals(
                PurchaseResult.Pending,
                NativeStoreBackend(
                    FakeTransport {
                        ok("""{"status":"pending"}""")
                    },
                ).purchase("pro", options()),
            )
            assertEquals(
                PurchaseResult.Cancelled,
                NativeStoreBackend(
                    FakeTransport {
                        ok("""{"status":"cancelled"}""")
                    },
                ).purchase("pro", options()),
            )
        }

    @Test
    fun `store errors come back as Failed, never thrown`() =
        runBlocking {
            for ((wire, code) in mapOf(
                "productNotFound" to StoreErrorCode.ProductNotFound,
                "verificationFailed" to StoreErrorCode.VerificationFailed,
                "purchaseNotAllowed" to StoreErrorCode.PurchaseNotAllowed,
                "notBundled" to StoreErrorCode.NotBundled,
            )) {
                val result = NativeStoreBackend(FakeTransport { err(wire) }).purchase("pro", options())
                assertEquals(code, assertIs<PurchaseResult.Failed>(result).error.code, wire)
            }
            val odd = NativeStoreBackend(FakeTransport { ok("""{"status":"maybe"}""") }).purchase("pro", options())
            assertEquals(StoreErrorCode.Internal, assertIs<PurchaseResult.Failed>(odd).error.code)
            val empty = NativeStoreBackend(FakeTransport { ok("""{"status":"success"}""") }).purchase("pro", options())
            assertEquals(StoreErrorCode.Internal, assertIs<PurchaseResult.Failed>(empty).error.code)
        }

    @Test
    fun `malformed purchase arguments fail without a request`() =
        runBlocking {
            val transport = FakeTransport()
            val backend = NativeStoreBackend(transport)
            val blank = backend.purchase(" ", options())
            val zero = backend.purchase("coins", options { quantity = 0 })
            assertEquals(StoreErrorCode.InvalidArgument, assertIs<PurchaseResult.Failed>(blank).error.code)
            assertEquals(StoreErrorCode.InvalidArgument, assertIs<PurchaseResult.Failed>(zero).error.code)
            assertTrue(transport.requests.isEmpty())
        }

    @Test
    fun `list maps every owned transaction`() =
        runBlocking {
            val sub = transaction("premium.monthly", "autoRenewable", "9", ""","expirationTime":1702592000000""")
            val coins = transaction("coins", "consumable", "8", ""","originalTransactionId":"","quantity":5""")
            val list = NativeStoreBackend(FakeTransport { ok("[${transaction()},$sub,$coins]") }).purchases()
            assertEquals(listOf("pro", "premium.monthly", "coins"), list.map { it.productId })
            assertEquals(Instant.ofEpochMilli(1702592000000), list[1].expirationTime)
            assertEquals(5, list[2].quantity)
            // An empty original id falls back to the transaction's own.
            assertEquals("8", list[2].originalTransactionId)
        }

    @Test
    fun `entitlement maps state, renewal and transaction`() =
        runBlocking {
            val wire =
                """{"productId":"premium.monthly","state":"inGracePeriod","expirationTime":1702592000000,"willAutoRenew":true,
                   "purchase":${transaction("premium.monthly", "autoRenewable")}}"""
            val status = NativeStoreBackend(FakeTransport { ok(wire) }).entitlement("premium.monthly")
            assertEquals(EntitlementState.InGracePeriod, status.state)
            assertTrue(status.isActive)
            assertEquals(true, status.willAutoRenew)
            assertEquals("premium.monthly", status.purchase?.productId)
            for ((state, expected) in mapOf(
                "active" to EntitlementState.Active,
                "inBillingRetry" to EntitlementState.InBillingRetry,
                "expired" to EntitlementState.Expired,
                "revoked" to EntitlementState.Revoked,
                "somethingNew" to EntitlementState.NotOwned,
            )) {
                val other =
                    NativeStoreBackend(
                        FakeTransport { ok("""{"productId":"x","state":"$state"}""") },
                    ).entitlement("x")
                assertEquals(expected, other.state, state)
                assertEquals(expected == EntitlementState.Active, other.isActive, state)
            }
            assertFailsWith<StoreException> { NativeStoreBackend(FakeTransport()).entitlement("") }
            Unit
        }

    @Test
    fun `consume finishes a consumable by transaction id and refuses anything else`() =
        runBlocking {
            val transport = FakeTransport { ok("{}") }
            val backend = NativeStoreBackend(transport)
            val coins =
                NativeStoreBackend(
                    FakeTransport {
                        ok("[${transaction("coins", "consumable", "8")}]")
                    },
                ).purchases().single()
            backend.consume(coins)
            assertEquals(
                "8",
                transport.requests
                    .single()
                    .args["transactionId"]!!
                    .jsonPrimitive.content,
            )

            val pro: Purchase = coins.copy(productId = "pro", productType = ProductType.NonConsumable)
            val error = assertFailsWith<StoreException> { backend.consume(pro) }
            assertEquals(StoreErrorCode.InvalidArgument, error.code)
            assertEquals(1, transport.requests.size)
        }

    @Test
    fun `the unsupported backend fails purchases as a result and the rest as exceptions`() =
        runBlocking {
            val backend = UnsupportedStoreBackend("not here")
            assertEquals(
                StoreErrorCode.Unsupported,
                assertIs<PurchaseResult.Failed>(backend.purchase("pro", options())).error.code,
            )
            assertFailsWith<StoreException> { backend.purchases() }
            assertFalse(backend.isSupported)
        }
}
