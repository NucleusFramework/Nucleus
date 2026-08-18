package dev.nucleusframework.updater

import dev.nucleusframework.updater.internal.UpdateMarker
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class UpdateMarkerTest {
    @After
    fun cleanup() {
        UpdateMarker.delete()
    }

    @Test
    fun `write and read marker returns correct versions`() {
        UpdateMarker.write("1.0.0", "2.0.0")

        val result = UpdateMarker.read()
        assertNotNull(result)
        assertEquals("1.0.0", result!!.first)
        assertEquals("2.0.0", result.second)
    }

    @Test
    fun `read returns null when no marker exists`() {
        assertNull(UpdateMarker.read())
    }

    @Test
    fun `exists returns true when marker is written`() {
        UpdateMarker.write("1.0.0", "1.1.0")
        assertTrue(UpdateMarker.exists())
    }

    @Test
    fun `exists returns false when no marker exists`() {
        assertFalse(UpdateMarker.exists())
    }

    @Test
    fun `delete removes the marker`() {
        UpdateMarker.write("1.0.0", "1.1.0")
        assertTrue(UpdateMarker.exists())

        UpdateMarker.delete()
        assertFalse(UpdateMarker.exists())
        assertNull(UpdateMarker.read())
    }

    @Test
    fun `write overwrites existing marker`() {
        UpdateMarker.write("1.0.0", "1.1.0")
        UpdateMarker.write("1.1.0", "2.0.0")

        val result = UpdateMarker.read()
        assertNotNull(result)
        assertEquals("1.1.0", result!!.first)
        assertEquals("2.0.0", result.second)
    }

    @Test
    fun `malformed marker files are treated as missing`() {
        UpdateMarker.write("1.0.0", "2.0.0")
        val file =
            File(
                System.getProperty("user.home"),
                "Library/Application Support/${dev.nucleusframework.core.runtime.NucleusApp.appId}/nucleus-update-event",
            )
        if (!file.isFile) return
        file.writeText("not-a-pair\n")
        assertNull(UpdateMarker.read())
        file.writeText("previousVersion=only\n")
        assertNull(UpdateMarker.read())
        file.writeText("newVersion=2.0.0\n")
        assertNull(UpdateMarker.read())
    }

    @Test
    fun `pre-release versions are preserved`() {
        UpdateMarker.write("1.0.0-beta.1", "1.0.0-beta.2")

        val result = UpdateMarker.read()
        assertNotNull(result)
        assertEquals("1.0.0-beta.1", result!!.first)
        assertEquals("1.0.0-beta.2", result.second)
    }
}
