package dev.nucleusframework.desktop.application.internal

import org.junit.Assert.assertEquals
import org.junit.Test

class RuntimeCompressionLevelIdsTest {
    @Test
    fun `runtime compression levels expose jlink ids`() {
        assertEquals(0, RuntimeCompressionLevel.NO_COMPRESSION.id)
        assertEquals(1, RuntimeCompressionLevel.CONSTANT_STRING_SHARING.id)
        assertEquals(2, RuntimeCompressionLevel.ZIP.id)
    }
}
