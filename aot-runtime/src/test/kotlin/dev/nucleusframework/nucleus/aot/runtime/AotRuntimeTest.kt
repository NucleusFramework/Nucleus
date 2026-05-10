package dev.nucleusframework.nucleus.aot.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AotRuntimeTest {
    @Test
    fun `parses explicit mode property values`() {
        assertEquals(AotRuntimeMode.TRAINING, AotRuntime.parseModeProperty("train"))
        assertEquals(AotRuntimeMode.TRAINING, AotRuntime.parseModeProperty("training"))
        assertEquals(AotRuntimeMode.RUNTIME, AotRuntime.parseModeProperty("runtime"))
        assertEquals(AotRuntimeMode.RUNTIME, AotRuntime.parseModeProperty("run"))
        assertEquals(AotRuntimeMode.RUNTIME, AotRuntime.parseModeProperty("on"))
        assertEquals(AotRuntimeMode.OFF, AotRuntime.parseModeProperty("off"))
        assertEquals(AotRuntimeMode.OFF, AotRuntime.parseModeProperty("disabled"))
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
