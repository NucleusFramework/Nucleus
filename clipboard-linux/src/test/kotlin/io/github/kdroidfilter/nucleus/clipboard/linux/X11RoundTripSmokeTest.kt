package io.github.kdroidfilter.nucleus.clipboard.linux

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Integration smoke test — exercised locally when `DISPLAY` is set. Skipped
 * automatically otherwise so headless CI workers do not fail.
 *
 * Bypasses the ServiceLoader + session detection and drives the native X11
 * bridge directly so it works on Wayland hosts with Xwayland available.
 */
class X11RoundTripSmokeTest {
    @Test
    fun textRoundTrip() {
        val display = System.getenv("DISPLAY") ?: return
        if (display.isEmpty()) return
        if (!NativeX11ClipboardBridge.isLoaded) return
        if (!NativeX11ClipboardBridge.nativeInit()) {
            println("X11 init failed — skipping smoke test")
            return
        }
        println("X11 bridge initialized, changeCount=${NativeX11ClipboardBridge.nativeChangeCount()}")

        val probe = "nucleus-smoke-${System.currentTimeMillis()}"
        val wrote = NativeX11ClipboardBridge.nativeWrite(probe, null, null, null, null, false)
        assertTrue(wrote, "native write should succeed")

        // Give the X server a moment to propagate the ownership.
        Thread.sleep(SETTLE_MS)

        val got = NativeX11ClipboardBridge.nativeReadText()
        assertEquals(probe, got?.trim(), "round-trip text mismatch")

        val targets = NativeX11ClipboardBridge.nativeAvailableTargets().toSet()
        println("targets=$targets")
        assertTrue("UTF8_STRING" in targets || "text/plain;charset=utf-8" in targets)
    }

    private companion object {
        const val SETTLE_MS: Long = 150L
    }
}
