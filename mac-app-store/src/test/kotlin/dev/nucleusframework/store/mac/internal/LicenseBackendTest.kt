package dev.nucleusframework.store.mac.internal

import dev.nucleusframework.store.mac.AppLicenseStatus
import dev.nucleusframework.store.mac.StoreEnvironment
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LicenseBackendTest {
    private val app =
        """{"bundleId":"dev.app","appId":"6834297624","appVersion":"7","originalAppVersion":"3",
           "originalPurchaseTime":1700000000000,"environment":"sandbox","jws":"a.b.c"}"""

    private fun license(answer: String) = runBlocking { NativeStoreBackend(FakeTransport { answer }).appLicense() }

    @Test
    fun `a valid licence carries the app transaction`() {
        val license = license(ok("""{"status":"valid","app":$app}"""))
        assertEquals(AppLicenseStatus.Valid, license.status)
        assertTrue(license.isValid)
        assertNull(license.reason)
        assertEquals("dev.app", license.appTransaction?.bundleId)
        assertEquals(StoreEnvironment.Sandbox, license.appTransaction?.environment)
    }

    @Test
    fun `each failed check maps to its status with the reason`() {
        val copied = license(ok("""{"status":"invalid","reason":"made for another Mac","app":$app}"""))
        assertEquals(AppLicenseStatus.Invalid, copied.status)
        assertEquals("made for another Mac", copied.reason)
        assertFalse(copied.isValid)
        assertEquals(AppLicenseStatus.Revoked, license(ok("""{"status":"revoked","app":$app}""")).status)
        val offline = license(ok("""{"status":"unavailable","reason":"no app transaction"}"""))
        assertEquals(AppLicenseStatus.Unavailable, offline.status)
        assertNull(offline.appTransaction)
    }

    @Test
    fun `an unknown status is never a licence`() {
        assertEquals(AppLicenseStatus.Invalid, license(ok("""{"status":"maybe"}""")).status)
    }

    @Test
    fun `a bridge failure is unavailable, never thrown`() {
        val failed = license(err("platform", "bridge down"))
        assertEquals(AppLicenseStatus.Unavailable, failed.status)
        assertEquals("bridge down", failed.reason)
        val unsupported = runBlocking { UnsupportedStoreBackend("not macOS").appLicense() }
        assertEquals(AppLicenseStatus.Unavailable, unsupported.status)
    }
}
