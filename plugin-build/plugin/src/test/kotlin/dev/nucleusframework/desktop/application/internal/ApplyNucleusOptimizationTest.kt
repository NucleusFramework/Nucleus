package dev.nucleusframework.desktop.application.internal

import dev.nucleusframework.desktop.application.dsl.GarbageCollector
import org.gradle.testfixtures.ProjectBuilder
import org.junit.Assert.assertEquals
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
        assertEquals(listOf(OPTIMIZED_XMS, OPTIMIZED_MAX_RAM_PERCENTAGE), app.jvmArgs.toList())
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
        assertEquals(listOf("-Xms64m", "-XX:MaxRAMPercentage=40"), app.jvmArgs.toList())
    }

    private fun applicationData(): JvmApplicationData {
        val project = ProjectBuilder.builder().build()
        return project.objects.newInstance(JvmApplicationData::class.java)
    }
}
