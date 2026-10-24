package dev.nucleusframework.desktop.application.dsl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
    fun `only G1 is restricted to Oracle GraalVM, and off Linux to 25_4`() {
        assertTrue(NativeImageGarbageCollector.G1.isOracleOnly)
        assertEquals("25.4", NativeImageGarbageCollector.G1.nonLinuxMinVersion)
        listOf(NativeImageGarbageCollector.SERIAL, NativeImageGarbageCollector.EPSILON).forEach { gc ->
            assertFalse("$gc should be unrestricted", gc.isOracleOnly)
            assertNull("$gc should be unrestricted", gc.nonLinuxMinVersion)
        }
    }

    @Test
    fun `heap percentage option follows the collector`() {
        assertEquals("MaximumHeapSizePercent", NativeImageGarbageCollector.SERIAL.maxHeapPercentOption)
        assertEquals("MaximumHeapSizePercent", NativeImageGarbageCollector.EPSILON.maxHeapPercentOption)
        assertEquals("MaxRAMPercentage", NativeImageGarbageCollector.G1.maxHeapPercentOption)
    }
}
