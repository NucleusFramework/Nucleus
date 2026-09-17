package dev.nucleusframework.core.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Paths

class SingleInstanceConfigurationTest {
    @Test
    fun `configuration derives lock and restore filenames from the identifier`() {
        val dir = Paths.get(System.getProperty("java.io.tmpdir"), "nucleus-si-test")
        val config =
            SingleInstanceManager.Configuration(
                lockFilesDir = dir,
                lockIdentifier = "kover-lock",
            )
        assertEquals("kover-lock.lock", config.lockFileName)
        assertEquals("kover-lock.restore_request", config.restoreRequestFileName)
        assertEquals(dir.resolve("kover-lock.lock"), config.lockFilePath)
        assertEquals(dir.resolve("kover-lock.restore_request"), config.restoreRequestFilePath)
    }

    @Test
    fun `default configuration uses tmpdir and a non-blank identifier`() {
        val config = SingleInstanceManager.Configuration()
        assertEquals(Paths.get(System.getProperty("java.io.tmpdir")), config.lockFilesDir)
        assertTrue(config.lockIdentifier.isNotBlank())
        assertTrue(config.lockFileName.endsWith(".lock"))
    }
}
