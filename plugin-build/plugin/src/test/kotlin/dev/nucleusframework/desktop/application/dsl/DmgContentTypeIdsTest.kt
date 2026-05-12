package dev.nucleusframework.desktop.application.dsl

import org.junit.Assert.assertEquals
import org.junit.Test

class DmgContentTypeIdsTest {
    @Test
    fun `dmg content types expose electron builder ids`() {
        assertEquals("link", DmgContentType.Link.id)
        assertEquals("file", DmgContentType.File.id)
        assertEquals("dir", DmgContentType.Dir.id)
    }
}
