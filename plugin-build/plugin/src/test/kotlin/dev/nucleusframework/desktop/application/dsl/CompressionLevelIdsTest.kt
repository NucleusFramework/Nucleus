package dev.nucleusframework.desktop.application.dsl

import org.junit.Assert.assertEquals
import org.junit.Test

class CompressionLevelIdsTest {
    @Test
    fun `compression levels expose electron builder ids`() {
        assertEquals("store", CompressionLevel.Store.id)
        assertEquals("normal", CompressionLevel.Normal.id)
        assertEquals("maximum", CompressionLevel.Maximum.id)
    }
}
