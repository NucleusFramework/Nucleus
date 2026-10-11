package dev.nucleusframework.window.tao.deco

import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FullscreenOverlayVisibilityTest {
    @Test
    fun mouseFollowsTheTopEdge() {
        assertEquals(true, decide(visible = false, PointerType.Mouse, PointerEventType.Move, onBar = true))
        assertEquals(false, decide(visible = true, PointerType.Mouse, PointerEventType.Move, onBar = false))
        assertEquals(false, decide(visible = true, PointerType.Mouse, PointerEventType.Press, onBar = false))
    }

    @Test
    fun aTapAnywhereRevealsTheHiddenBar() {
        assertEquals(true, decide(visible = false, PointerType.Touch, PointerEventType.Press, onBar = false))
        assertEquals(true, decide(visible = false, PointerType.Touch, PointerEventType.Press, onBar = true))
    }

    @Test
    fun aTapOutsideTheShownBarHidesIt() {
        assertEquals(false, decide(visible = true, PointerType.Touch, PointerEventType.Press, onBar = false))
    }

    @Test
    fun aTapOnTheShownBarKeepsIt() {
        assertEquals(true, decide(visible = true, PointerType.Touch, PointerEventType.Press, onBar = true))
    }

    @Test
    fun touchMovesAndReleasesLeaveItAsIs() {
        assertNull(decide(visible = true, PointerType.Touch, PointerEventType.Move, onBar = false))
        assertNull(decide(visible = false, PointerType.Touch, PointerEventType.Release, onBar = true))
    }

    @Test
    fun aPenTapsLikeAFinger() {
        assertEquals(true, decide(visible = false, PointerType.Stylus, PointerEventType.Press, onBar = false))
        assertEquals(true, decide(visible = false, PointerType.Eraser, PointerEventType.Press, onBar = false))
        assertTrue(PointerType.Stylus.revealsByTap)
        assertFalse(PointerType.Mouse.revealsByTap)
        assertFalse(PointerType.Unknown.revealsByTap)
    }

    private fun decide(
        visible: Boolean,
        pointer: PointerType,
        event: PointerEventType,
        onBar: Boolean,
    ) = fullscreenOverlayVisibility(visible = visible, pointer = pointer, event = event, onBar = onBar)
}
