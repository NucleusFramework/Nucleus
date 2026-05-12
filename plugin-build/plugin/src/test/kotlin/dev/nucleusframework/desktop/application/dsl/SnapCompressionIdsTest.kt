package dev.nucleusframework.desktop.application.dsl

import org.junit.Assert.assertEquals
import org.junit.Test

class SnapCompressionIdsTest {
    @Test
    fun `snap compression values expose snapcraft ids`() {
        assertEquals("xz", SnapCompression.Xz.id)
        assertEquals("lzo", SnapCompression.Lzo.id)
    }
}
