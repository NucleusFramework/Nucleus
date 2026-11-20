package dev.nucleusframework.store.microsoft.internal

import dev.nucleusframework.store.microsoft.AppLicense
import dev.nucleusframework.store.microsoft.PurchaseOptions
import dev.nucleusframework.store.microsoft.PurchaseResult
import dev.nucleusframework.store.microsoft.StoreErrorCode
import dev.nucleusframework.store.microsoft.StoreException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The app's own license: [NativeStoreBackend.appLicense], `purchaseApp` and their updates. */
class AppLicenseBackendTest {
    private val transport = FakeTransport()
    private val backend = NativeStoreBackend(transport)

    private fun appLicenseJson(
        isTrial: Boolean = true,
        isActive: Boolean = true,
        remaining: Long? = 604_800_000,
    ) = """{"isActive":$isActive,"isTrial":$isTrial,"expirationTime":1767225600000,""" +
        (remaining?.let { """"trialTimeRemaining":$it,""" } ?: "") +
        """"isTrialOwnedByThisUser":true,"trialUniqueId":"trial-1","skuStoreId":"9NAPP/0010"}"""

    @Test
    fun `app license decodes a trial, an unlimited trial and a full license`() =
        runBlocking<Unit> {
            transport.answer = { ok(appLicenseJson()) }
            val trial = backend.appLicense()
            assertEquals("appLicense", transport.requests.last().method)
            assertTrue(trial.isTrial && trial.isActive && !trial.isFullLicense)
            assertEquals(Duration.ofDays(7), trial.trialTimeRemaining)
            assertEquals(Instant.ofEpochMilli(1_767_225_600_000), trial.expirationTime)
            assertTrue(trial.isTrialOwnedByThisUser)
            assertEquals("trial-1", trial.trialUniqueId)
            assertEquals("9NAPP/0010", trial.skuStoreId)

            transport.answer = { ok(appLicenseJson(remaining = null)) }
            assertNull(backend.appLicense().trialTimeRemaining)

            transport.answer = { ok("""{"isActive":true,"isTrial":false,"skuStoreId":"9NAPP/0010"}""") }
            val full = backend.appLicense()
            assertTrue(full.isFullLicense)
            assertNull(full.expirationTime)
            assertNull(full.trialUniqueId)

            // What the Store answers an install it did not license.
            transport.answer = { ok("""{"isActive":true,"isTrial":false,"trialUniqueId":"","skuStoreId":""}""") }
            val unlicensed = backend.appLicense()
            assertNull(unlicensed.skuStoreId)
            assertNull(unlicensed.trialUniqueId)
            assertTrue(!unlicensed.isFullLicense)

            transport.answer = { ok(appLicenseJson(isActive = false, remaining = 0)) }
            val expired = backend.appLicense()
            assertTrue(expired.isTrial && !expired.isActive && !expired.isFullLicense)
        }

    @Test
    fun `app license errors are StoreExceptions`() =
        runBlocking<Unit> {
            transport.answer = { err("notPackaged", hresult = 0x80073D54) }
            assertEquals(StoreErrorCode.NotPackaged, assertFailsWith<StoreException> { backend.appLicense() }.code)
            transport.available = false
            assertEquals(StoreErrorCode.Unsupported, assertFailsWith<StoreException> { backend.appLicense() }.code)
        }

    @Test
    fun `purchaseApp forwards the owner window and maps outcomes without throwing`() =
        runBlocking<Unit> {
            val appPurchase = """{"productId":"9NAPP","productType":"nonConsumable","state":"purchased"}"""
            val cases =
                listOf(
                    ok("""{"status":"success","purchase":$appPurchase}""") to PurchaseResult.Success::class,
                    ok("""{"status":"alreadyOwned","purchase":$appPurchase}""") to PurchaseResult.Success::class,
                    ok("""{"status":"cancelled"}""") to PurchaseResult.Cancelled::class,
                    err("cancelled") to PurchaseResult.Cancelled::class,
                    err("notAssociated") to PurchaseResult.Failed::class,
                    "garbage" to PurchaseResult.Failed::class,
                )
            for ((envelope, type) in cases) {
                transport.answer = { envelope }
                assertEquals(type, backend.purchaseApp(PurchaseOptions())::class, envelope)
            }
            transport.answer = { ok("""{"status":"success","purchase":$appPurchase}""") }
            val success =
                assertIs<PurchaseResult.Success>(
                    backend.purchaseApp(PurchaseOptions().apply { windows { ownerWindowHandle = 0x42L } }),
                )
            assertEquals("9NAPP", success.purchase.productId)
            val sent = transport.requests.last()
            assertEquals("purchaseApp", sent.method)
            assertEquals(0x42L, sent.args["ownerWindow"]!!.jsonPrimitive.long)
            backend.purchaseApp(PurchaseOptions())
            val bare = transport.requests.last()
            assertTrue(bare.args.isEmpty())

            transport.available = false
            assertEquals(
                StoreErrorCode.Unsupported,
                assertIs<PurchaseResult.Failed>(backend.purchaseApp(PurchaseOptions())).error.code,
            )
        }

    @Test
    fun `app license updates report state changes only, against the baseline`() =
        runBlocking<Unit> {
            transport.answer = { seen -> if (seen.method == "appLicense") ok(appLicenseJson()) else ok("[]") }
            val collected = mutableListOf<AppLicense>()
            val collector =
                launch(start = CoroutineStart.UNDISPATCHED) { backend.appLicenseUpdates.collect { collected += it } }
            withTimeout(5_000) { while (transport.requests.none { it.method == "appLicense" }) delay(1) }
            delay(50)

            // Same trial, less time left, plus an add-on change: no app license news.
            transport.event(
                """{"type":"licensesChanged","appLicense":${appLicenseJson(remaining = 1_000)},""" +
                    """"purchases":[{"productId":"pro","productType":"nonConsumable","state":"purchased"}]}""",
            )
            // An add-on event without an app license (unreadable) changes nothing either.
            transport.event("""{"type":"licensesChanged","purchases":[]}""")
            // The trial converts.
            transport.event(
                """{"type":"licensesChanged","appLicense":${appLicenseJson(isTrial = false, remaining = null)}}""",
            )
            // Repeated: already known.
            transport.event(
                """{"type":"licensesChanged","appLicense":${appLicenseJson(isTrial = false, remaining = null)}}""",
            )
            withTimeout(5_000) { while (collected.isEmpty()) delay(1) }
            delay(100)
            collector.cancel()
            assertEquals(1, collected.size, collected.toString())
            assertTrue(collected.single().isFullLicense)
            assertEquals(1, transport.startUpdatesCalls.get())
        }

    @Test
    fun `without a baseline the first app license event is reported`() =
        runBlocking<Unit> {
            transport.answer = { err("network") }
            val first = async(start = CoroutineStart.UNDISPATCHED) { backend.appLicenseUpdates.first() }
            withTimeout(5_000) { while (transport.requests.none { it.method == "appLicense" }) delay(1) }
            delay(50)
            transport.event("""{"type":"licensesChanged","appLicense":${appLicenseJson(isActive = false)}}""")
            assertTrue(!withTimeout(5_000) { first.await() }.isActive)
        }
}
