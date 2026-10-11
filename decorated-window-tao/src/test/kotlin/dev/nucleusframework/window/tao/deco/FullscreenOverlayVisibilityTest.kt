package dev.nucleusframework.window.tao.deco

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FullscreenOverlayVisibilityTest {
    @Test
    fun mouseFollowsTheTopEdge() {
        assertEquals(true, fullscreenOverlayVisibility(visible = false, touch = false, press = false, onBar = true))
        assertEquals(false, fullscreenOverlayVisibility(visible = true, touch = false, press = false, onBar = false))
        assertEquals(false, fullscreenOverlayVisibility(visible = true, touch = false, press = true, onBar = false))
    }

    @Test
    fun aTapAnywhereRevealsTheHiddenBar() {
        assertEquals(true, fullscreenOverlayVisibility(visible = false, touch = true, press = true, onBar = false))
        assertEquals(true, fullscreenOverlayVisibility(visible = false, touch = true, press = true, onBar = true))
    }

    @Test
    fun aTapOutsideTheShownBarHidesIt() {
        assertEquals(false, fullscreenOverlayVisibility(visible = true, touch = true, press = true, onBar = false))
    }

    @Test
    fun aTapOnTheShownBarKeepsIt() {
        assertEquals(true, fullscreenOverlayVisibility(visible = true, touch = true, press = true, onBar = true))
    }

    @Test
    fun touchMovesAndReleasesLeaveItAsIs() {
        assertNull(fullscreenOverlayVisibility(visible = true, touch = true, press = false, onBar = false))
        assertNull(fullscreenOverlayVisibility(visible = false, touch = true, press = false, onBar = true))
    }
}
