package io.github.kdroidfilter.nucleus.clipboard.linux

import kotlin.test.Test
import kotlin.test.assertNotNull

/**
 * Reads whatever image/png is currently on the Wayland clipboard. Runs only when
 * WAYLAND_DISPLAY is set and wl-clipboard is on PATH.
 */
class WaylandImageSmokeTest {
    @Test
    fun readCurrentImage() {
        System.getenv("WAYLAND_DISPLAY") ?: return
        val delegate = WaylandClipboardDelegate()
        if (!delegate.isAvailable) return
        println("types=${delegate.listTypes()}")
        val bytes = delegate.readImagePng()
        println("image bytes=${bytes?.size}")
        assertNotNull(bytes, "expected image/png on clipboard")
    }
}
