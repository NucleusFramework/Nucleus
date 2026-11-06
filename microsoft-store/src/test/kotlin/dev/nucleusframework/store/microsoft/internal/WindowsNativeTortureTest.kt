package dev.nucleusframework.store.microsoft.internal

import dev.nucleusframework.core.runtime.Platform
import dev.nucleusframework.store.microsoft.EntitlementState
import dev.nucleusframework.store.microsoft.ProductType
import dev.nucleusframework.store.microsoft.Purchase
import dev.nucleusframework.store.microsoft.PurchaseOptions
import dev.nucleusframework.store.microsoft.PurchaseResult
import dev.nucleusframework.store.microsoft.PurchaseState
import dev.nucleusframework.store.microsoft.StoreAvailability
import dev.nucleusframework.store.microsoft.StoreErrorCode
import dev.nucleusframework.store.microsoft.StoreException
import dev.nucleusframework.store.microsoft.StoreUpdateOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.awt.Frame
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Drives the real `nucleus_msstore.dll` through JNI from an unpackaged test JVM: the protocol, the
 * threading and the cancellation under load, and the real `Windows.Services.Store` API as far as
 * an app without a Store listing can take it. Skipped off Windows.
 */
class WindowsNativeTortureTest {
    private val enabled = Platform.Current == Platform.Windows && NativeStoreBridge.isAvailable
    private lateinit var backend: NativeStoreBackend

    @BeforeTest
    fun setUp() {
        if (enabled) backend = NativeStoreBackend(NativeStoreBridge)
    }

    @AfterTest
    fun tearDown() {
        if (!enabled) return
        runBlocking<Unit> {
            backend.diagnostic("diag.allowUnpackaged", """{"value":false}""")
            // Every request, cancelled or not, must have been answered and forgotten.
            withTimeout(10_000) { while (NativeStoreBridge.inFlight() != 0 || backend.pendingCount != 0) delay(10) }
        }
    }

    private fun native(block: suspend CoroutineScope.() -> Unit) {
        if (!enabled) {
            println("Skipping: not Windows or nucleus_msstore.dll not loaded")
            return
        }
        runBlocking { block() }
    }

    private suspend fun echo(value: String): String {
        val args = buildJsonObject { put("s", value) }.toString()
        return backend.diagnostic("diag.echo", args).let {
            (it as kotlinx.serialization.json.JsonObject)["s"]!!.jsonPrimitive.content
        }
    }

    // -- the unpackaged contract ------------------------------------------------------------------

    @Test
    fun `an unpackaged process is told so on every call`() =
        native {
            assertEquals(StoreAvailability.NotPackaged, backend.availability())
            val calls: List<suspend () -> Any> =
                listOf(
                    { backend.products(listOf("pro")) },
                    { backend.purchases() },
                    { backend.restorePurchases() },
                    { backend.entitlement("pro") },
                    { backend.appLicense() },
                    { backend.checkUpdates() },
                    { backend.openStorePage() },
                    { backend.installUpdates(StoreUpdateOptions()).toList() },
                )
            for (call in calls) {
                val e = assertFailsWith<StoreException> { call() }
                assertEquals(StoreErrorCode.NotPackaged, e.code)
                assertEquals(0x80073D54.toInt(), e.platformCode)
            }
            val failed = assertIs<PurchaseResult.Failed>(backend.purchase("pro", PurchaseOptions()))
            assertEquals(StoreErrorCode.NotPackaged, failed.error.code)
            val failedApp = assertIs<PurchaseResult.Failed>(backend.purchaseApp(PurchaseOptions()))
            assertEquals(StoreErrorCode.NotPackaged, failedApp.error.code)
            assertEquals(false, NativeStoreBridge.startUpdates())
        }

    // -- the real Store API, as far as an unlisted app gets ------------------------------------------

    @Test
    fun `the real Store API fails typed for an app without a listing`() =
        native {
            backend.diagnostic("diag.allowUnpackaged", """{"value":true}""")
            assertEquals(
                StoreErrorCode.NotAssociated,
                assertFailsWith<StoreException> { backend.products(listOf("pro")) }.code,
            )
            // Licenses are local: they read fine, and are empty.
            assertEquals(emptyList(), backend.purchases())
            assertEquals(EntitlementState.NotOwned, backend.entitlement("pro").state)
            // The Store answers an unlicensed install with an active, non-trial license and no
            // SKU: it must not read as bought.
            val license = backend.appLicense()
            assertTrue(license.isActive && !license.isTrial)
            assertNull(license.skuStoreId)
            assertFalse(license.isFullLicense)
            // Update checks are not local: an app without a listing gets 0x803F6101 from the Store.
            val updates = assertFailsWith<StoreException> { backend.checkUpdates() }
            assertEquals(StoreErrorCode.Platform, updates.code)
            assertEquals(0x803F6101.toInt(), updates.platformCode)
            val coins =
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
                    "9NBLGGH69M0B|{01234567-89AB-CDEF-0123-456789ABCDEF}",
                )
            assertEquals(
                StoreErrorCode.NotAssociated,
                assertFailsWith<StoreException> { backend.consume(coins, 1) }.code,
            )
            for (token in listOf(
                "garbage",
                "9N|not-a-guid",
                "|{01234567-89AB-CDEF-0123-456789ABCDEF}",
                "9N/0001|{01234567-89AB-CDEF-0123-456789ABCDEF}",
            )) {
                assertEquals(
                    StoreErrorCode.InvalidArgument,
                    assertFailsWith<StoreException> {
                        backend.consume(coins.copy(consumeToken = token), 1)
                    }.code,
                    token,
                )
            }
        }

    @Test
    fun `a purchase needs a window of this process`() =
        native {
            backend.diagnostic("diag.allowUnpackaged", """{"value":true}""")
            val noWindow = assertIs<PurchaseResult.Failed>(backend.purchase("pro", PurchaseOptions()))
            assertEquals(StoreErrorCode.NoOwnerWindow, noWindow.error.code)
            // 0x10010 is the desktop window's usual HWND: real, visible, another process's.
            for (bogus in listOf(0x7FFF_FFF0L, -1L, 0x10010L)) {
                val options = PurchaseOptions().apply { windows { ownerWindowHandle = bogus } }
                assertEquals(
                    StoreErrorCode.NoOwnerWindow,
                    assertIs<PurchaseResult.Failed>(backend.purchase("pro", options)).error.code,
                    "$bogus",
                )
            }
            if (java.awt.GraphicsEnvironment.isHeadless()) return@native
            val frame =
                Frame("nucleus-iap torture").apply {
                    setSize(200, 120)
                    isVisible = true
                }
            try {
                delay(300)
                // With a window the owner resolves and the call gets as far as the Store itself.
                val failed = assertIs<PurchaseResult.Failed>(backend.purchase("pro", PurchaseOptions()))
                assertEquals(StoreErrorCode.NotAssociated, failed.error.code)
            } finally {
                frame.dispose()
            }
        }

    @Test
    fun `hundreds of concurrent real Store calls with random cancellation`() =
        native {
            backend.diagnostic("diag.allowUnpackaged", """{"value":true}""")
            val outcomes = AtomicInteger()
            (0 until 400)
                .map { i ->
                    async(Dispatchers.Default) {
                        val limit = if (i % 3 == 0) ThreadLocalRandom.current().nextLong(0, 4) else 30_000
                        val outcome =
                            runCatching {
                                withTimeoutOrNull(limit) {
                                    when (i % 4) {
                                        0 -> backend.products(listOf("p$i"))
                                        1 -> backend.purchases()
                                        2 -> backend.entitlement("p$i")
                                        else -> backend.availability()
                                    }
                                }
                            }
                        // Products fail (no listing); everything else answers or was timed out.
                        outcome.exceptionOrNull()?.let { e ->
                            assertIs<StoreException>(e)
                            assertEquals(StoreErrorCode.NotAssociated, e.code)
                        }
                        if (i % 4 != 0) assertTrue(outcome.isSuccess, "call $i: ${outcome.exceptionOrNull()}")
                        outcomes.incrementAndGet()
                    }
                }.awaitAll()
            assertEquals(400, outcomes.get())
        }

    // -- JNI protocol torture ---------------------------------------------------------------------

    @Test
    fun `strings survive the JNI round trip`() =
        native {
            val samples =
                listOf(
                    "",
                    "plain",
                    "quote\" backslash\\ slash/ tab\t newline\n cr\r",
                    "nul\u0000inside",
                    "\u0001\u001f\u007f\u0080ÿ",
                    "é€£¥₹₩₫ «guillemets» ‹›",
                    "עברית العربية", // right-to-left
                    "‮‏​",
                    "😀🏳️‍🌈👨‍👩‍👧‍👦",
                    "𝄞 􏿿", // supplementary: G clef, last code point
                    "𝕏".repeat(10_000),
                )
            for (sample in samples) assertEquals(sample, echo(sample))
            val everyBmpChar = buildString { for (c in 1..0xFFFF) if (c !in 0xD800..0xDFFF) append(c.toChar()) }
            assertEquals(everyBmpChar, echo(everyBmpChar))
        }

    @Test
    fun `lone surrogates do not break the bridge`() =
        native {
            for (broken in listOf("\uD800", "a\uDC00b", "\uDFFF\uD800")) {
                // Either delivered (replaced) or refused as an argument; never a crash.
                runCatching { echo(broken) }.onFailure { assertIs<StoreException>(it) }
            }
            assertEquals("still alive", echo("still alive"))
        }

    @Test
    fun `an eight megabyte payload round trips`() =
        native {
            val big = "x".repeat(8 * 1024 * 1024)
            assertEquals(big.length, echo(big).length)
        }

    @Test
    fun `malformed arguments are refused, not fatal`() =
        native {
            for (args in listOf("{not json", "", "[]", "\"str\"", "{\"ids\":5}")) {
                val method = if (args.contains("ids")) "products" else "diag.echo"
                backend.diagnostic("diag.allowUnpackaged", """{"value":true}""")
                val outcome = runCatching { backend.diagnostic(method, args) }
                outcome.onFailure { assertIs<StoreException>(it) }
            }
            assertEquals(
                StoreErrorCode.InvalidArgument,
                assertFailsWith<StoreException> { backend.diagnostic("diag.echo", "{not json") }.code,
            )
            assertEquals(
                StoreErrorCode.InvalidArgument,
                assertFailsWith<StoreException> { backend.diagnostic("products", """{"ids":5}""") }.code,
            )
            assertEquals(
                StoreErrorCode.InvalidArgument,
                assertFailsWith<StoreException> { backend.diagnostic("no.such.method", "{}") }.code,
            )
        }

    @Test
    fun `a native panic answers the request and the JVM lives on`() =
        native {
            repeat(50) {
                val e =
                    assertFailsWith<StoreException> {
                        backend.diagnostic(
                            "diag.panic",
                            """{"message":"torture $it"}""",
                        )
                    }
                assertEquals(StoreErrorCode.Internal, e.code)
                assertTrue(e.message!!.contains("torture $it"))
            }
            assertEquals("after", echo("after"))
        }

    @Test
    fun `native errors keep their HRESULT`() =
        native {
            val e =
                assertFailsWith<StoreException> {
                    backend.diagnostic(
                        "diag.fail",
                        """{"message":"m","hresult":-2143330041}""",
                    )
                }
            assertEquals(0x803F6107.toInt(), e.platformCode)
        }

    @Test
    fun `two thousand concurrent native requests with random cancellation`() =
        native {
            val cancelled = AtomicInteger()
            (0 until 2_000)
                .map { i ->
                    async(Dispatchers.Default) {
                        val millis = ThreadLocalRandom.current().nextLong(0, 30)
                        val limit = if (i % 4 == 0) ThreadLocalRandom.current().nextLong(0, 20) else 60_000
                        val value =
                            withTimeoutOrNull(limit) {
                                backend.diagnostic("diag.delay", """{"millis":$millis,"value":$i}""")
                            }
                        if (value == null) cancelled.incrementAndGet() else assertEquals(JsonPrimitive(i), value)
                    }
                }.awaitAll()
            println("cancelled ${cancelled.get()} of 2000")
        }

    @Test
    fun `cancellation reaches a blocked native request promptly`() =
        native {
            val started = System.nanoTime()
            (0 until 300)
                .map {
                    async(Dispatchers.Default) {
                        withTimeoutOrNull(20) { backend.diagnostic("diag.delay", """{"millis":600000,"value":0}""") }
                    }
                }.awaitAll()
            // tearDown then waits for every native worker to wind down; a missed cancel would
            // keep one sleeping for ten minutes and time that out.
            assertTrue((System.nanoTime() - started) / 1_000_000 < 10_000)
        }

    @Test
    fun `diagnostics report the backend`() =
        native {
            val info = backend.diagnostic("diag.info", "{}") as kotlinx.serialization.json.JsonObject
            assertEquals("windows.services.store", info["backend"]!!.jsonPrimitive.content)
        }
}
