package dev.nucleusframework.desktop.application.internal

import dev.nucleusframework.desktop.application.dsl.GarbageCollector
import org.gradle.testfixtures.ProjectBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ApplyNucleusOptimizationTest {
    @Test
    fun `disabled does not touch collector or heap flags`() {
        val app = applicationData()
        applyNucleusOptimization(app)
        assertNull(app.garbageCollector)
        assertTrue(app.jvmArgs.isEmpty())
    }

    @Test
    fun `enabled sets serial and heap when unset`() {
        val app = applicationData()
        app.nucleusOptimization = true
        applyNucleusOptimization(app)
        assertEquals(GarbageCollector.SERIAL, app.garbageCollector)
        assertEquals(
            listOf(OPTIMIZED_XMS, OPTIMIZED_MAX_RAM_PERCENTAGE, OPTIMIZED_IDLE_GC_FLAG),
            app.jvmArgs.toList(),
        )
    }

    @Test
    fun `enabled keeps an explicit collector and existing heap flags`() {
        val app = applicationData()
        app.nucleusOptimization = true
        app.garbageCollector = GarbageCollector.G1
        app.jvmArgs.add("-Xms64m")
        app.jvmArgs.add("-XX:MaxRAMPercentage=40")
        applyNucleusOptimization(app)
        assertEquals(GarbageCollector.G1, app.garbageCollector)
        assertEquals(
            listOf("-Xms64m", "-XX:MaxRAMPercentage=40", OPTIMIZED_IDLE_GC_FLAG),
            app.jvmArgs.toList(),
        )
    }

    @Test
    fun `enabled does not duplicate an existing runtime flag`() {
        val app = applicationData()
        app.nucleusOptimization = true
        app.jvmArgs.add("-Dnucleus.optimization.idleGc=false")
        applyNucleusOptimization(app)
        assertEquals(1, app.jvmArgs.count { it.startsWith("-Dnucleus.optimization.idleGc=") })
        assertTrue(app.jvmArgs.contains("-Dnucleus.optimization.idleGc=false"))
    }

    @Test
    fun `master on idleGc off omits the runtime flag`() {
        val app = applicationData()
        app.nucleusOptimization = true
        app.nucleusOptimizationSettings.idleGc = false
        applyNucleusOptimization(app)
        assertEquals(GarbageCollector.SERIAL, app.garbageCollector)
        assertEquals(listOf(OPTIMIZED_XMS, OPTIMIZED_MAX_RAM_PERCENTAGE), app.jvmArgs.toList())
        assertFalse(app.optIdleGc)
        assertTrue(app.optSingleJar)
    }

    @Test
    fun `master on serialGc off leaves collector unset`() {
        val app = applicationData()
        app.nucleusOptimization = true
        app.nucleusOptimizationSettings.serialGc = false
        applyNucleusOptimization(app)
        assertNull(app.garbageCollector)
        assertTrue(app.optCompactHeap)
        assertTrue(app.optIdleGc)
        assertFalse(app.optSerialGc)
    }

    @Test
    fun `only idleGc sets the runtime flag`() {
        val app = applicationData()
        app.nucleusOptimizationSettings.idleGc = true
        applyNucleusOptimization(app)
        assertNull(app.garbageCollector)
        assertEquals(listOf(OPTIMIZED_IDLE_GC_FLAG), app.jvmArgs.toList())
        assertFalse(app.optSerialGc)
        assertFalse(app.optCompactHeap)
        assertFalse(app.optSingleJar)
    }

    @Test
    fun `only serialGc sets the collector`() {
        val app = applicationData()
        app.nucleusOptimizationSettings.serialGc = true
        applyNucleusOptimization(app)
        assertEquals(GarbageCollector.SERIAL, app.garbageCollector)
        assertTrue(app.jvmArgs.isEmpty())
    }

    @Test
    fun `only compactHeap sets heap flags`() {
        val app = applicationData()
        app.nucleusOptimizationSettings.compactHeap = true
        applyNucleusOptimization(app)
        assertNull(app.garbageCollector)
        assertEquals(listOf(OPTIMIZED_XMS, OPTIMIZED_MAX_RAM_PERCENTAGE), app.jvmArgs.toList())
        assertFalse(app.optIdleGc)
    }

    @Test
    fun `only singleJar does not touch jvm flags`() {
        val app = applicationData()
        app.nucleusOptimizationSettings.singleJar = true
        applyNucleusOptimization(app)
        assertNull(app.garbageCollector)
        assertTrue(app.jvmArgs.isEmpty())
        assertTrue(app.optSingleJar)
        assertFalse(app.optIdleGc)
    }

    private fun applicationData(): JvmApplicationData {
        val project = ProjectBuilder.builder().build()
        return project.objects.newInstance(JvmApplicationData::class.java)
    }
}
