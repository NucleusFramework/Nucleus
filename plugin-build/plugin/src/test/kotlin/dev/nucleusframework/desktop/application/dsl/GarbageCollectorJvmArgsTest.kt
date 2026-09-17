package dev.nucleusframework.desktop.application.dsl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GarbageCollectorJvmArgsTest {
    @Test
    fun `garbage collectors expose their HotSpot flags`() {
        assertEquals(listOf("-XX:+UseSerialGC"), GarbageCollector.SERIAL.jvmArgs)
        assertEquals(listOf("-XX:+UseParallelGC"), GarbageCollector.PARALLEL.jvmArgs)
        assertEquals(listOf("-XX:+UseG1GC"), GarbageCollector.G1.jvmArgs)
        assertEquals(listOf("-XX:+UseZGC"), GarbageCollector.Z.jvmArgs)
        assertEquals(listOf("-XX:+UseShenandoahGC"), GarbageCollector.SHENANDOAH.jvmArgs)
    }

    @Test
    fun `epsilon unlocks experimental options before selecting itself`() {
        assertEquals(
            listOf("-XX:+UnlockExperimentalVMOptions", "-XX:+UseEpsilonGC"),
            GarbageCollector.EPSILON.jvmArgs,
        )
    }

    @Test
    fun `every collector selects exactly one collector flag`() {
        GarbageCollector.entries.forEach { gc ->
            val collectorFlags = gc.jvmArgs.filter { it.startsWith("-XX:+Use") }
            assertEquals("unexpected collector flags for $gc: $collectorFlags", 1, collectorFlags.size)
            assertTrue("$gc does not end with a GC flag", collectorFlags.single().endsWith("GC"))
        }
    }
}
