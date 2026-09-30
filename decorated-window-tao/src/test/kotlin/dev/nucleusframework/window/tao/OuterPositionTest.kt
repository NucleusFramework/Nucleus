package dev.nucleusframework.window.tao

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OuterPositionTest {
    @Test
    fun `positions on any real desktop are placeable, off the main screen included`() {
        assertTrue(isPlaceableOuterPosition(0.0, 0.0))
        assertTrue(isPlaceableOuterPosition(1170.5, 212.25))
        // A monitor left of or above the main one
        assertTrue(isPlaceableOuterPosition(-2560.0, -1440.0))
    }

    @Test
    fun `a sentinel or a broken computation is not a position`() {
        // SeforimApp's "unspecified" geometry reached setOuterPosition as-is and aborted the app on macOS
        assertFalse(isPlaceableOuterPosition(Int.MIN_VALUE.toDouble(), Int.MIN_VALUE.toDouble()))
        assertFalse(isPlaceableOuterPosition(Int.MAX_VALUE.toDouble(), 0.0))
        assertFalse(isPlaceableOuterPosition(Double.NaN, 0.0))
        assertFalse(isPlaceableOuterPosition(0.0, Double.POSITIVE_INFINITY))
        assertFalse(isPlaceableOuterPosition(0.0, -MAX_OUTER_POSITION_DP - 1))
    }
}
