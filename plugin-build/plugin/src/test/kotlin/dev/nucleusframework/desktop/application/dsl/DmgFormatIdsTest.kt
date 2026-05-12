package dev.nucleusframework.desktop.application.dsl

import org.junit.Assert.assertEquals
import org.junit.Test

class DmgFormatIdsTest {
    @Test
    fun `dmg formats expose hdiutil ids`() {
        assertEquals("UDRW", DmgFormat.UDRW.id)
        assertEquals("UDZO", DmgFormat.UDZO.id)
        assertEquals("ULFO", DmgFormat.ULFO.id)
    }
}
