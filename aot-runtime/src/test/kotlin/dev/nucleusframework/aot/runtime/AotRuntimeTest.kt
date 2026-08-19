package dev.nucleusframework.aot.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AotRuntimeTest {
    @Test
    fun `parses explicit mode property values`() {
        assertEquals(AotRuntimeMode.TRAINING, AotRuntime.parseModeProperty("train"))
        assertEquals(AotRuntimeMode.TRAINING, AotRuntime.parseModeProperty("training"))
        assertEquals(AotRuntimeMode.RUNTIME, AotRuntime.parseModeProperty("runtime"))
        assertEquals(AotRuntimeMode.RUNTIME, AotRuntime.parseModeProperty("run"))
        assertEquals(AotRuntimeMode.RUNTIME, AotRuntime.parseModeProperty("on"))
        assertEquals(AotRuntimeMode.RUNTIME, AotRuntime.parseModeProperty("use"))
        assertEquals(AotRuntimeMode.RUNTIME, AotRuntime.parseModeProperty("enabled"))
        assertEquals(AotRuntimeMode.OFF, AotRuntime.parseModeProperty("off"))
        assertEquals(AotRuntimeMode.OFF, AotRuntime.parseModeProperty("disabled"))
        assertEquals(AotRuntimeMode.OFF, AotRuntime.parseModeProperty("none"))
    }

    @Test
    fun `mode helpers follow nucleus aot mode system property`() {
        val key = "nucleus.aot.mode"
        val previous = System.getProperty(key)
        try {
            System.clearProperty(key)
            assertEquals(AotRuntimeMode.OFF, AotRuntime.mode())
            assertFalse(AotRuntime.isRuntime())
            assertFalse(AotRuntime.isTraining())

            System.setProperty(key, "training")
            assertEquals(AotRuntimeMode.TRAINING, AotRuntime.mode())
            assertTrue(AotRuntime.isTraining())
            assertFalse(AotRuntime.isRuntime())

            System.setProperty(key, "use")
            assertEquals(AotRuntimeMode.RUNTIME, AotRuntime.mode())
            assertTrue(AotRuntime.isRuntime())
            assertFalse(AotRuntime.isTraining())

            System.setProperty(key, "not-a-mode")
            assertEquals(AotRuntimeMode.OFF, AotRuntime.mode())
        } finally {
            if (previous == null) {
                System.clearProperty(key)
            } else {
                System.setProperty(key, previous)
            }
        }
    }

    @Test
    fun `ignores case and whitespace in mode property`() {
        assertEquals(AotRuntimeMode.TRAINING, AotRuntime.parseModeProperty("  TRAINING  "))
        assertEquals(AotRuntimeMode.RUNTIME, AotRuntime.parseModeProperty("Runtime"))
    }

    @Test
    fun `returns null for empty or unknown mode property`() {
        assertNull(AotRuntime.parseModeProperty(null))
        assertNull(AotRuntime.parseModeProperty(""))
        assertNull(AotRuntime.parseModeProperty("unknown"))
    }
}
