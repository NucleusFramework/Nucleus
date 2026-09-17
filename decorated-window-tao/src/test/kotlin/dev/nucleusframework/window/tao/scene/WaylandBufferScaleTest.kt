package dev.nucleusframework.window.tao.scene

import kotlin.test.Test
import kotlin.test.assertEquals

class WaylandBufferScaleTest {
    @Test
    fun `scale of one leaves every positive size unchanged except zero`() {
        assertEquals(1, alignToBufferScale(0, 1))
        assertEquals(1, alignToBufferScale(1, 1))
        assertEquals(17, alignToBufferScale(17, 1))
    }

    @Test
    fun `already aligned sizes are kept`() {
        assertEquals(2, alignToBufferScale(2, 2))
        assertEquals(100, alignToBufferScale(100, 2))
        assertEquals(96, alignToBufferScale(96, 3))
    }

    @Test
    fun `odd sizes round up to the next multiple of the scale`() {
        assertEquals(2, alignToBufferScale(1, 2))
        assertEquals(102, alignToBufferScale(101, 2))
        assertEquals(62, alignToBufferScale(61, 2))
        assertEquals(99, alignToBufferScale(97, 3))
    }

    @Test
    fun `sizes at or below the scale collapse to the scale itself`() {
        assertEquals(2, alignToBufferScale(0, 2))
        assertEquals(2, alignToBufferScale(2, 2))
        assertEquals(3, alignToBufferScale(1, 3))
        assertEquals(3, alignToBufferScale(3, 3))
    }

    @Test
    fun `non-positive scale is treated as one`() {
        assertEquals(1, alignToBufferScale(0, 0))
        assertEquals(1, alignToBufferScale(1, 0))
        assertEquals(8, alignToBufferScale(8, -2))
        assertEquals(1, alignToBufferScale(0, -1))
    }
}
