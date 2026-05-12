package dev.nucleusframework.desktop.application.dsl

import org.junit.Assert.assertEquals
import org.junit.Test

class SnapConfinementIdsTest {
    @Test
    fun `snap confinement values expose manifest ids`() {
        assertEquals("strict", SnapConfinement.Strict.id)
        assertEquals("classic", SnapConfinement.Classic.id)
        assertEquals("devmode", SnapConfinement.Devmode.id)
    }
}
