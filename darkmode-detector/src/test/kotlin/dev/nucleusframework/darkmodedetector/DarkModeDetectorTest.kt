package dev.nucleusframework.darkmodedetector

import dev.nucleusframework.core.runtime.Platform
import java.util.function.Consumer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DarkModeDetectorTest {
    @Test
    fun `platform detector reports a boolean and accepts listeners`() {
        val detector = getPlatformDarkModeDetector()
        val dark = detector.isDark()
        assertEquals(dark, detector.isDark())

        var seen: Boolean? = null
        val listener = Consumer<Boolean> { seen = it }
        detector.registerListener(listener)
        detector.removeListener(listener)
        assertEquals(null, seen)
    }

    @Test
    fun `noop detector is always light and ignores listeners`() {
        var called = false
        val listener = Consumer<Boolean> { called = true }
        assertFalse(NoopDarkModeDetector.isDark())
        NoopDarkModeDetector.registerListener(listener)
        NoopDarkModeDetector.removeListener(listener)
        assertFalse(called)
    }

    @Test
    fun `unknown platform would use the noop detector`() {
        if (Platform.Current == Platform.Unknown) {
            assertTrue(getPlatformDarkModeDetector() === NoopDarkModeDetector)
        } else {
            assertTrue(getPlatformDarkModeDetector() !== NoopDarkModeDetector)
        }
    }
}
