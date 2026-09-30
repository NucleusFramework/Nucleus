package dev.nucleusframework.desktop.application.internal

import dev.nucleusframework.desktop.application.dsl.GarbageCollector
import org.gradle.api.Project
import org.gradle.testfixtures.ProjectBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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

    @Test
    fun `disabled does not provision a JDK`() {
        val project = ProjectBuilder.builder().build()
        val app = applicationData(project)
        applyNucleusOptimizationJdk(project, app)
        assertNull(app.javaHomeOverride)
        assertFalse(app.optLastJdk)
    }

    @Test
    fun `master on provisions a lazy JDK home`() {
        val project = ProjectBuilder.builder().build()
        val app = applicationData(project)
        app.nucleusOptimization = true
        applyNucleusOptimizationJdk(project, app)
        assertTrue(app.optLastJdk)
        assertNotNull(app.javaHomeOverride)
    }

    @Test
    fun `explicit javaHome wins over JDK provisioning`() {
        val project = ProjectBuilder.builder().build()
        val app = applicationData(project)
        app.nucleusOptimization = true
        app.javaHome = "/custom/jdk"
        applyNucleusOptimizationJdk(project, app)
        assertNull(app.javaHomeOverride)
        assertEquals("/custom/jdk", app.javaHome)
    }

    @Test
    fun `master on lastJdk off does not provision`() {
        val project = ProjectBuilder.builder().build()
        val app = applicationData(project)
        app.nucleusOptimization = true
        app.nucleusOptimizationSettings.lastJdk = false
        applyNucleusOptimizationJdk(project, app)
        assertFalse(app.optLastJdk)
        assertNull(app.javaHomeOverride)
    }

    @Test
    fun `only lastJdk provisions without touching JVM flags`() {
        val project = ProjectBuilder.builder().build()
        val app = applicationData(project)
        app.nucleusOptimizationSettings.lastJdk = true
        applyNucleusOptimization(app)
        applyNucleusOptimizationJdk(project, app)
        assertNull(app.garbageCollector)
        assertTrue(app.jvmArgs.isEmpty())
        assertNotNull(app.javaHomeOverride)
    }

    private fun applicationData(project: Project = ProjectBuilder.builder().build()): JvmApplicationData =
        project.objects.newInstance(JvmApplicationData::class.java)
}
