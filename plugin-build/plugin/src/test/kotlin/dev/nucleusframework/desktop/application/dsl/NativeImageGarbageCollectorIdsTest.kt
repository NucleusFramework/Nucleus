package dev.nucleusframework.desktop.application.dsl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeImageGarbageCollectorIdsTest {
    @Test
    fun `collectors expose native-image gc flags`() {
        assertEquals("--gc=serial", NativeImageGarbageCollector.SERIAL.flag)
        assertEquals("--gc=G1", NativeImageGarbageCollector.G1.flag)
        assertEquals("--gc=epsilon", NativeImageGarbageCollector.EPSILON.flag)
    }

    @Test
    fun `only G1 is restricted to Oracle GraalVM on Linux`() {
        assertTrue(NativeImageGarbageCollector.G1.isOracleOnly)
        assertTrue(NativeImageGarbageCollector.G1.isLinuxOnly)
        listOf(NativeImageGarbageCollector.SERIAL, NativeImageGarbageCollector.EPSILON).forEach { gc ->
            assertFalse("$gc should be unrestricted", gc.isOracleOnly)
            assertFalse("$gc should be unrestricted", gc.isLinuxOnly)
        }
    }

    @Test
    fun `heap percentage option follows the collector`() {
        assertEquals("MaximumHeapSizePercent", NativeImageGarbageCollector.SERIAL.maxHeapPercentOption)
        assertEquals("MaximumHeapSizePercent", NativeImageGarbageCollector.EPSILON.maxHeapPercentOption)
        assertEquals("MaxRAMPercentage", NativeImageGarbageCollector.G1.maxHeapPercentOption)
    }
}
