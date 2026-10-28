package dev.nucleusframework.iap.internal

import dev.nucleusframework.iap.EntitlementState
import dev.nucleusframework.iap.PeriodUnit
import dev.nucleusframework.iap.ProductType
import dev.nucleusframework.iap.Purchase
import dev.nucleusframework.iap.PurchaseOptions
import dev.nucleusframework.iap.PurchaseResult
import dev.nucleusframework.iap.PurchaseState
import dev.nucleusframework.iap.PurchaseUpdate
import dev.nucleusframework.iap.StoreAvailability
import dev.nucleusframework.iap.StoreErrorCode
import dev.nucleusframework.iap.StoreException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.math.BigDecimal
import java.time.Instant
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

class NativeStoreBackendTest {
    private val transport = FakeTransport()
    private val backend = NativeStoreBackend(transport)

    private fun purchaseJson(
        id: String = "pro",
        type: String = "nonConsumable",
        extra: String = "",
    ) = """{"productId":"$id","productType":"$type","state":"purchased"$extra}"""

    private fun consumable(token: String? = "9N|{00000000-0000-0000-0000-000000000001}") =
        Purchase(
            "coins",
            ProductType.Consumable,
            null,
            null,
            null,
            null,
            null,
            1,
            null,
            PurchaseState.Purchased,
            null,
            token,
        )

    // -- mapping ------------------------------------------------------------------------------

    @Test
    fun `products keep the caller's order and drop blanks, repeats and strays`() =
        runBlocking<Unit> {
            transport.answer = {
                ok(
                    """[{"id":"b","type":"consumable","displayPrice":"1"},{"id":"zzz"},""" +
                        """{"id":"a","type":"nonConsumable"},{"id":"a"}]""",
                )
            }
            val products = backend.products(listOf("a", " ", "b", "a", "missing", ""))
            assertEquals(listOf("a", "b"), products.map { it.id })
            val sent =
                transport.requests
                    .single()
                    .args["ids"]!!
                    .jsonArray
                    .map { it.jsonPrimitive.content }
            assertEquals(listOf("a", "b", "missing"), sent)
        }

    @Test
    fun `no ids means no native call`() =
        runBlocking<Unit> {
            assertEquals(emptyList(), backend.products(listOf("", "  ")))
            assertTrue(transport.requests.isEmpty())
        }

    @Test
    fun `a full product decodes`() =
        runBlocking<Unit> {
            transport.answer = {
                ok(
                    """[{"id":"pro","title":"Pro","description":"All","type":"autoRenewable","displayPrice":"4,99 €",
                    "price":"4.99","currencyCode":"EUR","platformId":"9N","subscription":{"period":{"value":1,"unit":"month"},
                    "introductoryOffer":{"displayPrice":"Gratuit","price":"0","period":{"value":7,"unit":"day"},"periodCount":1,
                    "paymentMode":"freeTrial"}},"future":{"field":true}}]""",
                )
            }
            val p = backend.products(listOf("pro")).single()
            assertEquals(ProductType.AutoRenewableSubscription, p.type)
            assertEquals(BigDecimal("4.99"), p.price)
            assertEquals("EUR", p.currencyCode)
            assertEquals(PeriodUnit.Month, p.subscription!!.period.unit)
            assertEquals(
                "P7D",
                p.subscription!!
                    .introductoryOffer!!
                    .period
                    .toIso8601(),
            )
            assertEquals(BigDecimal.ZERO, p.subscription!!.introductoryOffer!!.price)
        }

    @Test
    fun `unknown enums and unreadable prices degrade instead of failing`() =
        runBlocking<Unit> {
            transport.answer = {
                ok(
                    """[{"id":"x","type":"hologram","price":"4,99","currencyCode":"",""" +
                        """"subscription":{"period":{"value":3,"unit":"fortnight"}}}]""",
                )
            }
            val p = backend.products(listOf("x")).single()
            assertEquals(ProductType.Unknown, p.type)
            assertNull(p.price)
            assertNull(p.currencyCode)
            assertEquals(PeriodUnit.Unknown, p.subscription!!.period.unit)
            assertNull(p.subscription!!.period.toIso8601())
        }

    @Test
    fun `every wire error code maps and carries the platform code`() =
        runBlocking<Unit> {
            val expected =
                mapOf(
                    "unsupported" to StoreErrorCode.Unsupported,
                    "notPackaged" to StoreErrorCode.NotPackaged,
                    "notAssociated" to StoreErrorCode.NotAssociated,
                    "noOwnerWindow" to StoreErrorCode.NoOwnerWindow,
                    "productNotFound" to StoreErrorCode.ProductNotFound,
                    "network" to StoreErrorCode.Network,
                    "server" to StoreErrorCode.Server,
                    "insufficientQuantity" to StoreErrorCode.InsufficientQuantity,
                    "verificationFailed" to StoreErrorCode.VerificationFailed,
                    "invalidArgument" to StoreErrorCode.InvalidArgument,
                    "platform" to StoreErrorCode.Platform,
                    "cancelled" to StoreErrorCode.Platform,
                    "internal" to StoreErrorCode.Internal,
                    "brand-new-code" to StoreErrorCode.Internal,
                )
            for ((wire, code) in expected) {
                transport.answer = { err(wire, "m", 0x803F6107) }
                val e = assertFailsWith<StoreException> { backend.purchases() }
                assertEquals(code, e.code, wire)
                assertEquals(0x803F6107.toInt(), e.platformCode)
            }
        }

    @Test
    fun `malformed envelopes are Internal store errors, never anything else`() =
        runBlocking<Unit> {
            val garbage =
                listOf(
                    "",
                    "not json",
                    "[]",
                    "{}",
                    """{"ok":null}""",
                    """{"error":null}""",
                    """{"ok":{"not":"a list"}}""",
                    """{"ok":[{"no-product-id":1}]}""",
                    """{"ok":[{"productId":null}]}""",
                    """{"ok":[{"productId":"p","quantity":"many"}]}""",
                    """{"ok":[1,2,3]}""",
                    """{"error":{"code":42}}""",
                    "\u0000",
                    "{".repeat(10_000),
                )
            for (envelope in garbage) {
                transport.answer = { envelope }
                val e =
                    try {
                        backend.purchases()
                        fail("accepted ${envelope.take(40)}")
                    } catch (e: StoreException) {
                        e
                    }
                assertTrue(e.code == StoreErrorCode.Internal, "${envelope.take(40)} -> ${e.code}")
            }
        }

    @Test
    fun `an error envelope without fields still becomes a StoreException`() =
        runBlocking<Unit> {
            transport.answer = { """{"error":{}}""" }
            val e = assertFailsWith<StoreException> { backend.purchases() }
            assertEquals(StoreErrorCode.Internal, e.code)
        }

    @Test
    fun `availability maps states and short-circuits without a bridge`() =
        runBlocking<Unit> {
            for ((wire, state) in mapOf(
                "available" to StoreAvailability.Available,
                "paymentsRestricted" to StoreAvailability.PaymentsRestricted,
                "notPackaged" to StoreAvailability.NotPackaged,
                "?" to StoreAvailability.Unsupported,
            )) {
                transport.answer = { ok("""{"state":"$wire"}""") }
                assertEquals(state, backend.availability())
            }
            transport.available = false
            val before = transport.requests.size
            assertEquals(StoreAvailability.Unsupported, backend.availability())
            assertEquals(before, transport.requests.size)
            assertEquals(StoreErrorCode.Unsupported, assertFailsWith<StoreException> { backend.purchases() }.code)
        }

    // -- purchase ------------------------------------------------------------------------------

    @Test
    fun `purchase outcomes`() =
        runBlocking<Unit> {
            val cases =
                listOf(
                    ok("""{"status":"success","purchase":${purchaseJson()}}""") to PurchaseResult.Success::class,
                    ok("""{"status":"alreadyOwned","purchase":${purchaseJson()}}""") to PurchaseResult.Success::class,
                    ok("""{"status":"pending"}""") to PurchaseResult.Pending::class,
                    ok("""{"status":"cancelled"}""") to PurchaseResult.Cancelled::class,
                    err("cancelled") to PurchaseResult.Cancelled::class,
                    err("network") to PurchaseResult.Failed::class,
                    ok("""{"status":"success"}""") to PurchaseResult.Failed::class,
                    ok("""{"status":"teleported"}""") to PurchaseResult.Failed::class,
                    ok("""{"status":"failed","error":{"code":"verificationFailed","message":"bad jws"}}""") to
                        PurchaseResult.Failed::class,
                    ok("""[]""") to PurchaseResult.Failed::class,
                    "garbage" to PurchaseResult.Failed::class,
                )
            for ((envelope, type) in cases) {
                transport.answer = { envelope }
                val result = backend.purchase("pro", PurchaseOptions())
                assertEquals(type, result::class, envelope)
            }
            transport.answer = { ok("""{"status":"alreadyOwned","purchase":${purchaseJson()}}""") }
            assertTrue((backend.purchase("pro", PurchaseOptions()) as PurchaseResult.Success).alreadyOwned)
            transport.answer =
                { ok("""{"status":"failed","error":{"code":"verificationFailed","message":"bad jws"}}""") }
            assertEquals(
                StoreErrorCode.VerificationFailed,
                (backend.purchase("pro", PurchaseOptions()) as PurchaseResult.Failed).error.code,
            )
        }

    @Test
    fun `purchase never throws a store failure, even when the bridge is gone`() =
        runBlocking<Unit> {
            transport.available = false
            val result = backend.purchase("pro", PurchaseOptions())
            assertEquals(StoreErrorCode.Unsupported, assertIs<PurchaseResult.Failed>(result).error.code)
            transport.available = true
            transport.throwOnRequest = UnsatisfiedLinkError("gone")
            val failed = assertIs<PurchaseResult.Failed>(backend.purchase("pro", PurchaseOptions()))
            assertEquals(StoreErrorCode.Internal, failed.error.code)
            assertIs<UnsatisfiedLinkError>(failed.error.cause)
            assertEquals(0, backend.pendingCount)
        }

    @Test
    fun `purchase validates before calling native`() =
        runBlocking<Unit> {
            assertEquals(
                StoreErrorCode.InvalidArgument,
                assertIs<PurchaseResult.Failed>(backend.purchase(" ", PurchaseOptions())).error.code,
            )
            assertTrue(transport.requests.isEmpty())
        }

    @Test
    fun `purchase options reach native and unset ones are omitted`() =
        runBlocking<Unit> {
            transport.answer = { ok("""{"status":"cancelled"}""") }
            backend.purchase(
                "pro",
                PurchaseOptions().apply {
                    windows {
                        ownerWindowHandle = 0x1234L
                        skuStoreId = "9N/0010"
                    }
                },
            )
            backend.purchase("pro", PurchaseOptions())
            val (full, bare) = transport.requests.toList()
            assertEquals(0x1234L, full.args["ownerWindow"]!!.jsonPrimitive.long)
            assertEquals("9N/0010", full.args["skuStoreId"]!!.jsonPrimitive.content)
            assertEquals(setOf("productId"), bare.args.keys)
        }

    // -- entitlement / consume ------------------------------------------------------------------

    @Test
    fun `entitlement decodes and validates`() =
        runBlocking<Unit> {
            transport.answer =
                {
                    ok(
                        """{"productId":"pro","state":"inGracePeriod",""" +
                            """"expirationTime":1700000000000,"willAutoRenew":true}""",
                    )
                }
            val status = backend.entitlement("pro")
            assertEquals(EntitlementState.InGracePeriod, status.state)
            assertTrue(status.isActive)
            assertEquals(Instant.ofEpochMilli(1_700_000_000_000), status.expirationTime)
            transport.answer = { ok("""{"productId":"pro","state":"inBillingRetry"}""") }
            assertEquals(false, backend.entitlement("pro").isActive)
            assertEquals(
                StoreErrorCode.InvalidArgument,
                assertFailsWith<StoreException> { backend.entitlement("") }.code,
            )
        }

    @Test
    fun `consume validates the purchase and forwards token and quantity`() =
        runBlocking<Unit> {
            transport.answer = { ok("""{"balanceRemaining":0}""") }
            backend.consume(consumable(), 2)
            val sent = transport.requests.single().args
            assertEquals("9N|{00000000-0000-0000-0000-000000000001}", sent["token"]!!.jsonPrimitive.content)
            assertEquals(2, sent["quantity"]!!.jsonPrimitive.int)

            val bad =
                listOf(
                    consumable() to 0,
                    consumable() to -5,
                    consumable(token = null) to 1,
                    consumable().copy(productType = ProductType.NonConsumable) to 1,
                    consumable().copy(productType = ProductType.Unknown) to 1,
                )
            for ((purchase, quantity) in bad) {
                assertEquals(
                    StoreErrorCode.InvalidArgument,
                    assertFailsWith<StoreException> {
                        backend.consume(purchase, quantity)
                    }.code,
                )
            }
            assertEquals(1, transport.requests.size)
        }

    // -- concurrency / cancellation ---------------------------------------------------------------

    @Test
    fun `cancelling the caller cancels the native request exactly once`() =
        runBlocking<Unit> {
            transport.mode = FakeTransport.Mode.Held
            val result = withTimeoutOrNull(100) { backend.purchases() }
            assertNull(result)
            assertEquals(1, transport.cancels.size)
            assertEquals(0, backend.pendingCount)
            // The native side still answers the cancelled id; it must be ignored.
            val id = transport.requests.single().id
            transport.respond(id, ok("[]"))
            transport.respond(id, ok("[]"))
            assertEquals(0, backend.pendingCount)
        }

    @Test
    fun `a cancelled purchase leaves no in-flight marker behind`() =
        runBlocking<Unit> {
            transport.mode = FakeTransport.Mode.Held
            assertNull(withTimeoutOrNull(50) { backend.purchase("pro", PurchaseOptions()) })
            // A license snapshot with the product after the aborted purchase is news again.
            transport.event("""{"type":"licensesChanged","purchases":[]}""")
            val updates =
                async(start = CoroutineStart.UNDISPATCHED) {
                    backend.updates.first()
                }
            transport.event("""{"type":"licensesChanged","purchases":[${purchaseJson()}]}""")
            assertIs<PurchaseUpdate.Purchased>(withTimeout(5_000) { updates.await() })
        }

    @Test
    fun `responses for unknown ids and duplicate responses are ignored`() {
        transport.mode = FakeTransport.Mode.Held
        backend.onResponse(-1, ok("[]"))
        backend.onResponse(Long.MAX_VALUE, "garbage")
        runBlocking<Unit> {
            val call = async(Dispatchers.Default) { backend.purchases() }
            while (transport.heldRequests().isEmpty()) yield()
            val id = transport.heldRequests().single().id
            transport.respond(id, ok("[${purchaseJson()}]"))
            transport.respond(id, ok("[]"))
            assertEquals("pro", call.await().single().productId)
        }
    }

    @Test
    fun `twenty thousand concurrent calls with random cancellation all resolve correctly`() =
        runBlocking<Unit> {
            transport.mode = FakeTransport.Mode.Async
            transport.maxDelayMillis = 3
            transport.answer =
                { seen -> ok("""{"productId":"${seen.args["productId"]!!.jsonPrimitive.content}","state":"active"}""") }
            val cancelled = AtomicInteger()
            val completed = AtomicInteger()
            val jobs =
                (0 until 20_000).map { i ->
                    async(Dispatchers.Default) {
                        val id = "p$i"
                        val limit = if (i % 5 == 0) ThreadLocalRandom.current().nextLong(0, 3) else 60_000
                        val status = withTimeoutOrNull(limit) { backend.entitlement(id) }
                        if (status == null) {
                            cancelled.incrementAndGet()
                        } else {
                            assertEquals(id, status.productId)
                            completed.incrementAndGet()
                        }
                    }
                }
            jobs.awaitAll()
            assertEquals(20_000, cancelled.get() + completed.get())
            assertTrue(completed.get() >= 16_000, "completed=${completed.get()}")
            assertEquals(0, backend.pendingCount)
            // Only calls that were actually abandoned asked native to cancel.
            assertTrue(transport.cancels.size <= cancelled.get())
            assertEquals(transport.cancels.size, transport.cancels.toSet().size)
        }

    @Test
    fun `synchronous re-entrant answers work under load`() =
        runBlocking<Unit> {
            transport.mode = FakeTransport.Mode.Sync
            transport.answer = { ok("[]") }
            (0 until 5_000).map { async(Dispatchers.Default) { backend.purchases() } }.awaitAll()
            assertEquals(0, backend.pendingCount)
        }

    @Test
    fun `a throwing transport never leaks a pending request`() =
        runBlocking<Unit> {
            transport.throwOnRequest = IllegalStateException("boom")
            repeat(
                100,
            ) { assertEquals(StoreErrorCode.Internal, assertFailsWith<StoreException> { backend.purchases() }.code) }
            assertEquals(0, backend.pendingCount)
        }

    @Test
    fun `caller cancellation surfaces as CancellationException, not StoreException`() =
        runBlocking<Unit> {
            transport.mode = FakeTransport.Mode.Held
            val job = async { backend.products(listOf("a")) }
            while (transport.heldRequests().isEmpty()) yield()
            job.cancel()
            assertFailsWith<CancellationException> { job.await() }
            assertEquals(0, backend.pendingCount)
        }

    // -- updates ------------------------------------------------------------------------------

    @Test
    fun `the listener starts once, on first subscription`() =
        runBlocking<Unit> {
            transport.answer = { ok("[]") }
            assertEquals(0, transport.startUpdatesCalls.get())
            val a = launch(start = CoroutineStart.UNDISPATCHED) { backend.updates.collect {} }
            val b = launch(start = CoroutineStart.UNDISPATCHED) { backend.updates.collect {} }
            a.cancel()
            b.cancel()
            launch(start = CoroutineStart.UNDISPATCHED) { backend.updates.collect {} }.cancel()
            assertEquals(1, transport.startUpdatesCalls.get())
        }

    @Test
    fun `StoreKit transactions become Purchased or Revoked`() =
        runBlocking<Unit> {
            val updates = async(start = CoroutineStart.UNDISPATCHED) { backend.updates.take(3).toList() }
            transport.event("""{"type":"transaction","purchase":${purchaseJson()}}""")
            transport.event("""{"type":"transaction","purchase":${purchaseJson(extra = ""","revocationTime":1""")}}""")
            transport.event("""{"type":"transaction","purchase":{"productId":"pro","state":"revoked"}}""")
            val (a, b, c) = withTimeout(5_000) { updates.await() }
            assertIs<PurchaseUpdate.Purchased>(a)
            assertIs<PurchaseUpdate.Revoked>(b)
            assertIs<PurchaseUpdate.Revoked>(c)
        }

    @Test
    fun `malformed and unknown events are dropped without breaking the stream`() =
        runBlocking<Unit> {
            val updates = async(start = CoroutineStart.UNDISPATCHED) { backend.updates.first() }
            for (junk in listOf(
                "",
                "nope",
                "[]",
                """{"type":"transaction"}""",
                """{"type":"alien"}""",
                """{"type":"transaction","purchase":{}}""",
            )) {
                transport.event(junk)
            }
            transport.event("""{"type":"transaction","purchase":${purchaseJson(id = "ok")}}""")
            assertEquals("ok", withTimeout(5_000) { updates.await() }.purchase.productId)
        }

    @Test
    fun `events from many native threads are all delivered`() =
        runBlocking<Unit> {
            val total = 16 * 2_000
            val updates = async(start = CoroutineStart.UNDISPATCHED) { backend.updates.take(total).toList() }
            val threads =
                (0 until 16).map { t ->
                    Thread {
                        repeat(
                            2_000,
                        ) { i ->
                            transport.event(
                                """{"type":"transaction","purchase":${purchaseJson(id = "t$t-$i")}}""",
                            )
                        }
                    }.apply { start() }
                }
            threads.forEach { it.join() }
            val received = withTimeout(30_000) { updates.await() }
            assertEquals(total, received.map { it.purchase.productId }.toSet().size)
        }

    @Test
    fun `Microsoft Store license changes are diffed against the baseline`() =
        runBlocking<Unit> {
            transport.answer = { ok("[${purchaseJson(id = "old")}]") }
            val updates = async(start = CoroutineStart.UNDISPATCHED) { backend.updates.take(2).toList() }
            // Wait for the baseline fetch the subscription triggered.
            withTimeout(5_000) { while (transport.requests.none { it.method == "purchases" }) delay(1) }
            delay(50)
            transport.event("""{"type":"licensesChanged","purchases":[${purchaseJson(id = "new")}]}""")
            val (first, second) = withTimeout(5_000) { updates.await() }.sortedBy { it is PurchaseUpdate.Revoked }
            assertEquals("new", assertIs<PurchaseUpdate.Purchased>(first).purchase.productId)
            assertEquals("old", assertIs<PurchaseUpdate.Revoked>(second).purchase.productId)
        }

    @Test
    fun `a Windows purchase is not reported twice through the license stream`() =
        runBlocking<Unit> {
            transport.answer = { ok("[]") }
            val collected = mutableListOf<PurchaseUpdate>()
            val collector = launch(start = CoroutineStart.UNDISPATCHED) { backend.updates.collect { collected += it } }
            withTimeout(5_000) { while (transport.requests.none { it.method == "purchases" }) delay(1) }
            delay(50)

            transport.mode = FakeTransport.Mode.Held
            val purchase = async(Dispatchers.Default) { backend.purchase("pro", PurchaseOptions()) }
            withTimeout(5_000) { while (transport.heldRequests().isEmpty()) yield() }
            // The license lands before the purchase call returns — the race the tracker absorbs.
            transport.event("""{"type":"licensesChanged","purchases":[${purchaseJson()}]}""")
            transport.respond(
                transport.heldRequests().single().id,
                ok("""{"status":"success","purchase":${purchaseJson()}}"""),
            )
            assertIs<PurchaseResult.Success>(purchase.await())
            transport.event("""{"type":"licensesChanged","purchases":[${purchaseJson()}]}""")
            delay(100)
            collector.cancel()
            assertEquals(emptyList(), collected)
        }
}
