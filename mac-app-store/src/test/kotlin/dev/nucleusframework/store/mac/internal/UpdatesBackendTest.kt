package dev.nucleusframework.store.mac.internal

import dev.nucleusframework.store.mac.PurchaseUpdate
import dev.nucleusframework.store.mac.StoreEnvironment
import dev.nucleusframework.store.mac.StoreErrorCode
import dev.nucleusframework.store.mac.StoreException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UpdatesBackendTest {
    private fun transactionEvent(
        productId: String,
        extra: String = "",
    ) = """{"type":"transaction","purchase":{"productId":"$productId","productType":"autoRenewable",
           "transactionId":"11","originalTransactionId":"9","purchaseTime":1700000000000,
           "environment":"xcode","jws":"a.b.c"$extra}}"""

    @Test
    fun `the first collector starts the native listener once, after the development session`() =
        runBlocking {
            val transport = FakeTransport { ok("{}") }
            val backend = NativeStoreBackend(transport, DevConfiguration("/p.storekit", "/x/StoreKitTest"))
            val first = launch(start = CoroutineStart.UNDISPATCHED) { backend.updates.collect {} }
            val second = launch(start = CoroutineStart.UNDISPATCHED) { backend.updates.collect {} }
            assertEquals(1, transport.startUpdatesCalls.get())
            assertEquals(listOf("dev.configure"), transport.methods())
            first.cancel()
            second.cancel()
        }

    @Test
    fun `transactions become Purchased, revoked ones Revoked`() =
        runBlocking {
            val transport = FakeTransport()
            val backend = NativeStoreBackend(transport)
            val received = async(start = CoroutineStart.UNDISPATCHED) { backend.updates.take(2).toList() }
            transport.event(transactionEvent("premium.monthly"))
            transport.event("""{"type":"somethingNew"}""")
            transport.event("not json")
            transport.event(transactionEvent("pro", ""","revocationTime":1700000600000"""))
            val (renewed, refunded) = received.await()
            val purchase = assertIs<PurchaseUpdate.Purchased>(renewed).purchase
            assertEquals("premium.monthly", purchase.productId)
            assertEquals("11", purchase.transactionId)
            assertEquals(StoreEnvironment.Xcode, purchase.environment)
            assertEquals(
                Instant.ofEpochMilli(1700000600000),
                assertIs<PurchaseUpdate.Revoked>(refunded).purchase.revocationTime,
            )
        }

    @Test
    fun `a listener that cannot start leaves the flow open and quiet`() =
        runBlocking {
            val transport = FakeTransport().apply { startUpdatesResult = false }
            val backend = NativeStoreBackend(transport)
            val job = launch(start = CoroutineStart.UNDISPATCHED) { backend.updates.collect {} }
            yield()
            assertTrue(job.isActive)
            job.cancel()
        }

    @Test
    fun `appTransaction maps the signed app record`() =
        runBlocking {
            val wire =
                """{"bundleId":"dev.app","appId":"1234567890","appVersion":"42","originalAppVersion":"7",
                   "originalPurchaseTime":1600000000000,"environment":"production","jws":"x.y.z"}"""
            val info = NativeStoreBackend(FakeTransport { ok(wire) }).appTransaction()
            assertEquals("dev.app", info.bundleId)
            assertEquals(1234567890L, info.appId)
            assertEquals("7", info.originalAppVersion)
            assertEquals(Instant.ofEpochMilli(1600000000000), info.originalPurchaseTime)
            assertEquals(StoreEnvironment.Production, info.environment)
            assertNull(info.revocationTime)
            assertEquals("x.y.z", info.signedAppTransaction)
        }

    @Test
    fun `openStorePage passes the id and reports a refusal`() =
        runBlocking {
            val transport = FakeTransport { ok("""{"opened":true}""") }
            val backend = NativeStoreBackend(transport)
            backend.openStorePage(1234567890L)
            assertEquals(
                "1234567890",
                transport.requests
                    .single()
                    .args["appId"]!!
                    .jsonPrimitive.content,
            )
            backend.openStorePage(null)
            assertNull(transport.requests.last().args["appId"])

            val refused = NativeStoreBackend(FakeTransport { ok("""{"opened":false}""") })
            assertEquals(StoreErrorCode.Platform, assertFailsWith<StoreException> { refused.openStorePage(1) }.code)
            assertEquals(
                StoreErrorCode.InvalidArgument,
                assertFailsWith<StoreException> { backend.openStorePage(0) }.code,
            )
        }

    @Test
    fun `the unsupported backend has no updates`() =
        runBlocking {
            val backend = UnsupportedStoreBackend("not here")
            assertEquals(emptyList(), backend.updates.toList())
            assertFailsWith<StoreException> { backend.appTransaction() }
            Unit
        }
}
