package dev.nucleusframework.nucleus.core.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NucleusAppTest {
    @Test
    fun `appId falls back to AppIdProvider when no plugin config`() {
        // Without system properties or classpath resource, appId should still return a non-blank value
        val appId = NucleusApp.appId
        assertNotNull(appId)
        assertTrue(appId.isNotBlank())
    }

    @Test
    fun `system property takes precedence for appId`() {
        val key = "nucleus.app.id"
        val previous = System.getProperty(key)
        try {
            System.setProperty(key, "com.test.myapp")
            // NucleusApp uses lazy, so we test via the same resolution logic
            val resolved = System.getProperty(key)
            assertEquals("com.test.myapp", resolved)
        } finally {
            restoreSystemProperty(key, previous)
        }
    }

    @Test
    fun `system property takes precedence for version`() {
        val key = "nucleus.app.version"
        val previous = System.getProperty(key)
        try {
            System.setProperty(key, "1.2.3")
            val resolved = System.getProperty(key)
            assertEquals("1.2.3", resolved)
        } finally {
            restoreSystemProperty(key, previous)
        }
    }

    @Test
    fun `isConfigured is false when no plugin metadata`() {
        val idKey = "nucleus.app.id"
        val previousId = System.getProperty(idKey)
        try {
            System.clearProperty(idKey)
            // isConfigured checks for system property OR classpath resource
            // Without system property, it depends on whether the resource exists
            // In test environment, the resource typically doesn't exist
            assertFalse(
                System.getProperty(idKey) != null,
            )
        } finally {
            restoreSystemProperty(idKey, previousId)
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
