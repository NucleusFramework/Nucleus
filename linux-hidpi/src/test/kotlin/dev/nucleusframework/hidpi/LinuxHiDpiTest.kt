package dev.nucleusframework.hidpi

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LinuxHiDpiTest {
    @Test
    fun `scale factor is zero off linux`() {
        if (System.getProperty("os.name").contains("Linux", ignoreCase = true)) {
            val scale = getLinuxNativeScaleFactor()
            assertTrue(scale >= 0.0)
            return
        }
        assertEquals(0.0, getLinuxNativeScaleFactor())
    }

    @Test
    fun `applyLinuxHiDpiScale is a no-op off linux`() {
        val previous = System.getProperty("sun.java2d.uiScale")
        try {
            applyLinuxHiDpiScale()
            if (!System.getProperty("os.name").contains("Linux", ignoreCase = true)) {
                assertEquals(previous, System.getProperty("sun.java2d.uiScale"))
            }
        } finally {
            if (previous == null) {
                System.clearProperty("sun.java2d.uiScale")
            } else {
                System.setProperty("sun.java2d.uiScale", previous)
            }
        }
    }

    @Test
    fun `applyLinuxHiDpiScale leaves an existing uiScale property alone`() {
        val previous = System.getProperty("sun.java2d.uiScale")
        try {
            System.setProperty("sun.java2d.uiScale", "1.5")
            applyLinuxHiDpiScale()
            assertEquals("1.5", System.getProperty("sun.java2d.uiScale"))
        } finally {
            if (previous == null) {
                System.clearProperty("sun.java2d.uiScale")
            } else {
                System.setProperty("sun.java2d.uiScale", previous)
            }
        }
    }
}
