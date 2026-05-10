package dev.nucleusframework.nucleus.updater

import dev.nucleusframework.nucleus.updater.internal.UpdateMarker
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class UpdateEventTest {
    private lateinit var updater: NucleusUpdater

    @Before
    fun setup() {
        updater =
            NucleusUpdater {
                currentVersion = "2.0.0"
                provider = FakeUpdateProvider()
            }
        UpdateMarker.delete()
    }

    @After
    fun cleanup() {
        UpdateMarker.delete()
    }

    @Test
    fun `consumeUpdateEvent returns null when no update happened`() {
        assertNull(updater.consumeUpdateEvent())
    }

    @Test
    fun `consumeUpdateEvent returns event with correct data`() {
        UpdateMarker.write("1.0.0", "2.0.0")

        val event = updater.consumeUpdateEvent()
        assertNotNull(event)
        assertEquals("1.0.0", event!!.previousVersion)
        assertEquals("2.0.0", event.newVersion)
        assertEquals(UpdateLevel.MAJOR, event.updateLevel)
    }

    @Test
    fun `consumeUpdateEvent deletes the marker`() {
        UpdateMarker.write("1.0.0", "2.0.0")
        updater.consumeUpdateEvent()

        assertNull(updater.consumeUpdateEvent())
    }

    @Test
    fun `wasJustUpdated returns true when marker exists`() {
        UpdateMarker.write("1.0.0", "2.0.0")
        assertTrue(updater.wasJustUpdated())
    }

    @Test
    fun `wasJustUpdated returns false when no marker exists`() {
        assertFalse(updater.wasJustUpdated())
    }

    @Test
    fun `wasJustUpdated still returns true after peek without consume`() {
        UpdateMarker.write("1.0.0", "2.0.0")
        assertTrue(updater.wasJustUpdated())
        assertTrue(updater.wasJustUpdated())
    }

    @Test
    fun `consumeUpdateEvent detects minor update level`() {
        UpdateMarker.write("1.0.0", "1.1.0")

        val event = updater.consumeUpdateEvent()
        assertNotNull(event)
        assertEquals(UpdateLevel.MINOR, event!!.updateLevel)
    }

    @Test
    fun `consumeUpdateEvent detects patch update level`() {
        UpdateMarker.write("1.0.0", "1.0.1")

        val event = updater.consumeUpdateEvent()
        assertNotNull(event)
        assertEquals(UpdateLevel.PATCH, event!!.updateLevel)
    }

    @Test
    fun `consumeUpdateEvent detects pre-release update level`() {
        UpdateMarker.write("1.0.0-beta.1", "1.0.0-beta.2")

        val event = updater.consumeUpdateEvent()
        assertNotNull(event)
        assertEquals(UpdateLevel.PRE_RELEASE, event!!.updateLevel)
    }
}
