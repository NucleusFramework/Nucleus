package dev.nucleusframework.store.mac.internal

import dev.nucleusframework.store.mac.IntroductoryOffer
import dev.nucleusframework.store.mac.OfferPaymentMode
import dev.nucleusframework.store.mac.PeriodUnit
import dev.nucleusframework.store.mac.ProductType
import dev.nucleusframework.store.mac.StoreAvailability
import dev.nucleusframework.store.mac.StoreErrorCode
import dev.nucleusframework.store.mac.StoreException
import dev.nucleusframework.store.mac.SubscriptionInfo
import dev.nucleusframework.store.mac.SubscriptionPeriod
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NativeStoreBackendTest {
    private val pro =
        """{"id":"pro","title":"Pro","description":"Everything","type":"nonConsumable",
           "displayPrice":"$4.99","price":"4.99","currencyCode":"USD"}"""
    private val monthly =
        """{"id":"premium.monthly","title":"Premium","description":"Monthly","type":"autoRenewable",
           "displayPrice":"$2.99","price":"2.99","currencyCode":"USD",
           "subscription":{"period":{"value":1,"unit":"month"},"groupId":"21000001",
             "introductoryOffer":{"displayPrice":"Free","price":"0","period":{"value":1,"unit":"week"},
               "periodCount":1,"paymentMode":"freeTrial"}}}"""

    @Test
    fun `availability maps every state and degrades unknown ones`() =
        runBlocking {
            val states =
                mapOf(
                    "available" to StoreAvailability.Available,
                    "paymentsRestricted" to StoreAvailability.PaymentsRestricted,
                    "notBundled" to StoreAvailability.NotBundled,
                    "somethingNew" to StoreAvailability.Unsupported,
                )
            for ((wire, expected) in states) {
                val backend = NativeStoreBackend(FakeTransport { ok("""{"state":"$wire"}""") })
                assertEquals(expected, backend.availability(), wire)
            }
        }

    @Test
    fun `an unavailable bridge reports Unsupported without a request`() =
        runBlocking {
            val transport = FakeTransport().apply { available = false }
            val backend = NativeStoreBackend(transport)
            assertEquals(StoreAvailability.Unsupported, backend.availability())
            val error = assertFailsWith<StoreException> { backend.products(listOf("pro")) }
            assertEquals(StoreErrorCode.Unsupported, error.code)
            assertTrue(transport.requests.isEmpty())
        }

    @Test
    fun `products keep the caller order and drop blank, repeated and unknown ids`() =
        runBlocking {
            val transport = FakeTransport { ok("[$monthly,$pro]") }
            val products = NativeStoreBackend(transport).products(listOf("pro", " ", "nope", "premium.monthly", "pro"))
            assertEquals(listOf("pro", "premium.monthly"), products.map { it.id })
            val asked =
                transport.requests
                    .single()
                    .args["ids"]!!
                    .jsonArray
                    .map { it.jsonPrimitive.content }
            assertEquals(listOf("pro", "nope", "premium.monthly"), asked)
        }

    @Test
    fun `no ids sends no request`() =
        runBlocking {
            val transport = FakeTransport()
            assertEquals(emptyList(), NativeStoreBackend(transport).products(listOf("", " ")))
            assertTrue(transport.requests.isEmpty())
        }

    @Test
    fun `a subscription maps its period, group and introductory offer`() =
        runBlocking {
            val backend = NativeStoreBackend(FakeTransport { ok("[$monthly]") })
            val product = backend.products(listOf("premium.monthly")).single()
            assertEquals(ProductType.AutoRenewableSubscription, product.type)
            assertEquals(BigDecimal("2.99"), product.price)
            assertEquals("USD", product.currencyCode)
            assertEquals(
                SubscriptionInfo(
                    period = SubscriptionPeriod(1, PeriodUnit.Month),
                    groupId = "21000001",
                    introductoryOffer =
                        IntroductoryOffer(
                            displayPrice = "Free",
                            price = BigDecimal.ZERO,
                            period = SubscriptionPeriod(1, PeriodUnit.Week),
                            periodCount = 1,
                            paymentMode = OfferPaymentMode.FreeTrial,
                        ),
                ),
                product.subscription,
            )
            assertEquals("P1M", product.subscription!!.period.toIso8601())
        }

    @Test
    fun `unknown enum values and unreadable prices degrade instead of failing`() =
        runBlocking {
            val odd = """{"id":"x","type":"bundle","displayPrice":"?","price":"n/a"}"""
            val product = NativeStoreBackend(FakeTransport { ok("[$odd]") }).products(listOf("x")).single()
            assertEquals(ProductType.Unknown, product.type)
            assertNull(product.price)
            assertNull(product.currencyCode)
        }

    @Test
    fun `native errors keep their code and platform code`() =
        runBlocking {
            val cases =
                mapOf(
                    "network" to StoreErrorCode.Network,
                    "notBundled" to StoreErrorCode.NotBundled,
                    "notAvailableInStorefront" to StoreErrorCode.NotAvailableInStorefront,
                    "invalidArgument" to StoreErrorCode.InvalidArgument,
                    "cancelled" to StoreErrorCode.Platform,
                    "whatever" to StoreErrorCode.Internal,
                )
            for ((wire, expected) in cases) {
                val backend = NativeStoreBackend(FakeTransport { err(wire, "boom", platformCode = -1009) })
                val error = assertFailsWith<StoreException> { backend.products(listOf("pro")) }
                assertEquals(expected, error.code, wire)
                assertEquals(-1009, error.platformCode)
            }
        }

    @Test
    fun `a malformed answer is an internal error`() =
        runBlocking {
            val error =
                assertFailsWith<StoreException> {
                    NativeStoreBackend(FakeTransport { "not json" }).products(listOf("pro"))
                }
            assertEquals(StoreErrorCode.Internal, error.code)
        }

    @Test
    fun `cancelling the caller cancels the native request and forgets it`() =
        runBlocking {
            val transport = FakeTransport(held = true)
            val backend = NativeStoreBackend(transport)
            val job = launch { backend.products(listOf("pro")) }
            while (transport.waitingRequests().isEmpty()) yield()
            val id = transport.waitingRequests().single().id
            job.cancel()
            job.join()
            assertEquals(listOf(id), transport.cancels.toList())
            assertEquals(0, backend.pendingCount)
            // A late answer to the cancelled request is dropped.
            backend.onResponse(id, ok("[$pro]"))
            assertEquals(0, backend.pendingCount)
        }

    @Test
    fun `the development session is configured once, before anything else`() =
        runBlocking {
            val transport = FakeTransport(held = true)
            val backend = NativeStoreBackend(transport, DevConfiguration("/tmp/Products.storekit", "/x/StoreKitTest"))
            val first = async { backend.products(listOf("pro")) }
            val second = async { backend.availability() }
            while (transport.waitingRequests().isEmpty()) yield()
            val configure = transport.waitingRequests().single()
            assertEquals("dev.configure", configure.method)
            assertEquals("/tmp/Products.storekit", configure.args["configuration"]!!.jsonPrimitive.content)
            assertEquals("/x/StoreKitTest", configure.args["framework"]!!.jsonPrimitive.content)
            transport.respond(configure.id, ok("{}"))
            while (transport.waitingRequests().size < 2) yield()
            for (request in transport.waitingRequests()) {
                val answer = if (request.method == "products") ok("[$pro]") else ok("""{"state":"available"}""")
                transport.respond(request.id, answer)
            }
            assertEquals("pro", first.await().single().id)
            assertEquals(StoreAvailability.Available, second.await())
            transport.held = false
            transport.answer = { ok("[$pro]") }
            backend.products(listOf("pro"))
            assertEquals(1, transport.methods().count { it == "dev.configure" })
            assertEquals("dev.configure", transport.methods().first())
        }

    @Test
    fun `a failed development session fails the call and is retried on the next one`() =
        runBlocking {
            var configureAnswers = 0
            val transport =
                FakeTransport { request ->
                    when (request.method) {
                        "dev.configure" -> if (configureAnswers++ == 0) err("invalidArgument", "bad file") else ok("{}")
                        else -> ok("[$pro]")
                    }
                }
            val backend = NativeStoreBackend(transport, DevConfiguration("/bad.storekit", "/x/StoreKitTest"))
            val error = assertFailsWith<StoreException> { backend.products(listOf("pro")) }
            assertEquals(StoreErrorCode.InvalidArgument, error.code)
            assertEquals("pro", backend.products(listOf("pro")).single().id)
            assertEquals(listOf("dev.configure", "dev.configure", "products"), transport.methods())
        }

    @Test
    fun `the unsupported backend fails every call`() =
        runBlocking {
            val backend = UnsupportedStoreBackend("not here")
            assertEquals(StoreAvailability.Unsupported, backend.availability())
            val error = assertFailsWith<StoreException> { backend.products(listOf("pro")) }
            assertEquals(StoreErrorCode.Unsupported, error.code)
        }
}
