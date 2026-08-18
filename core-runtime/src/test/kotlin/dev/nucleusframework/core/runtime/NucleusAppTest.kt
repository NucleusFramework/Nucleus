package dev.nucleusframework.core.runtime

import dev.nucleusframework.core.runtime.tools.LinuxDesktopFileDetector
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test

class NucleusAppTest {
    @Test
    fun `appId is a non-blank stable identifier`() {
        val appId = NucleusApp.appId
        assertNotNull(appId)
        assertTrue(appId.isNotBlank())
        assertEquals(appId, NucleusApp.appId)
    }

    @Test
    fun `system properties resolve into NucleusApp fields in documented order`() {
        assertEquals(TEST_VERSION, NucleusApp.version)
        assertEquals(TEST_VENDOR, NucleusApp.vendor)
        assertEquals(TEST_DESCRIPTION, NucleusApp.description)
        assertEquals(TEST_NAME, NucleusApp.appName)
        assertEquals(TEST_AUMID, NucleusApp.aumid)
        assertEquals(TEST_STARTUP_TASK, NucleusApp.startupTaskId)
    }

    @Test
    fun `aumid does not fall back to appId when aumid property is set`() {
        assertEquals(TEST_AUMID, NucleusApp.aumid)
        assertTrue(NucleusApp.appId.isNotBlank())
    }

    @Test
    fun `isConfigured is true when plugin system properties are present`() {
        assertTrue(NucleusApp.isConfigured)
        val desktop = LinuxDesktopFileDetector.desktopFilename
        if (desktop != null) {
            assertTrue(desktop.endsWith(".desktop"))
        }
    }

    @Test
    fun `blank system properties do not override already resolved values`() {
        val previous = System.getProperty(PROP_VERSION)
        try {
            System.setProperty(PROP_VERSION, "   ")
            assertEquals(TEST_VERSION, NucleusApp.version)
        } finally {
            restoreSystemProperty(PROP_VERSION, previous)
        }
    }

    companion object {
        private const val PROP_ID = "nucleus.app.id"
        private const val PROP_VERSION = "nucleus.app.version"
        private const val PROP_VENDOR = "nucleus.app.vendor"
        private const val PROP_DESCRIPTION = "nucleus.app.description"
        private const val PROP_NAME = "nucleus.app.name"
        private const val PROP_AUMID = "nucleus.app.aumid"
        private const val PROP_STARTUP_TASK = "nucleus.app.startup.task.id"

        private const val TEST_ID = "com.nucleus.kover.test"
        private const val TEST_VERSION = "3.1.4-kover"
        private const val TEST_VENDOR = "Kover Vendor"
        private const val TEST_DESCRIPTION = "Kover coverage fixture"
        private const val TEST_NAME = "Kover App"
        private const val TEST_AUMID = "com.nucleus.KoverApp"
        private const val TEST_STARTUP_TASK = "KoverStartupTask"

        private val previousValues = mutableMapOf<String, String?>()

        @JvmStatic
        @BeforeClass
        fun installPluginProperties() {
            listOf(
                PROP_ID to TEST_ID,
                PROP_VERSION to TEST_VERSION,
                PROP_VENDOR to TEST_VENDOR,
                PROP_DESCRIPTION to TEST_DESCRIPTION,
                PROP_NAME to TEST_NAME,
                PROP_AUMID to TEST_AUMID,
                PROP_STARTUP_TASK to TEST_STARTUP_TASK,
            ).forEach { (key, value) ->
                previousValues[key] = System.getProperty(key)
                System.setProperty(key, value)
            }
            // Force lazy resolution now so later tests cannot race the first read.
            check(NucleusApp.appId.isNotBlank())
            check(NucleusApp.version == TEST_VERSION)
            check(NucleusApp.vendor == TEST_VENDOR)
            check(NucleusApp.description == TEST_DESCRIPTION)
            check(NucleusApp.appName == TEST_NAME)
            check(NucleusApp.aumid == TEST_AUMID)
            check(NucleusApp.startupTaskId == TEST_STARTUP_TASK)
            check(NucleusApp.isConfigured)
        }

        @JvmStatic
        @AfterClass
        fun restorePluginProperties() {
            previousValues.forEach { (key, value) ->
                restoreSystemProperty(key, value)
            }
        }

        private fun restoreSystemProperty(
            name: String,
            value: String?,
        ) {
            if (value == null) {
                System.clearProperty(name)
            } else {
                System.setProperty(name, value)
            }
        }
    }
}
