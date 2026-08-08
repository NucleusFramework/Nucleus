package dev.nucleusframework.desktop.application.dsl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MsiSettingsTest {
    private fun msiSettings(): MsiSettings = object : MsiSettings() {}

    @Test
    fun `defaults to per-machine without marking the value as explicit`() {
        val msi = msiSettings()
        assertTrue(msi.perMachine)
        assertNull(msi.explicitPerMachine)
    }

    @Test
    fun `explicit value is tracked and returned`() {
        val msi = msiSettings()
        msi.perMachine = false
        assertFalse(msi.perMachine)
        assertEquals(false, msi.explicitPerMachine)

        msi.perMachine = true
        assertTrue(msi.perMachine)
        assertEquals(true, msi.explicitPerMachine)
    }
}
