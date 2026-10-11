package dev.nucleusframework.window.tao.deco

import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import kotlin.test.Test
import kotlin.test.assertEquals

class FullscreenOverlayVisibilityTest {
    @Test
    fun mouseFollowsTheTopEdgeAndCancelsAutoHide() {
        assertEquals(
            cancel(true),
            decide(false, PointerType.Mouse, PointerEventType.Move, inContact = false, onBar = true),
        )
        assertEquals(
            cancel(false),
            decide(true, PointerType.Mouse, PointerEventType.Move, inContact = false, onBar = false),
        )
        assertEquals(
            cancel(false),
            decide(true, PointerType.Mouse, PointerEventType.Press, inContact = true, onBar = false),
        )
    }

    @Test
    fun aTapAnywhereRevealsTheHiddenBar() {
        assertEquals(
            restart(),
            decide(false, PointerType.Touch, PointerEventType.Press, inContact = true, onBar = false),
        )
        assertEquals(
            restart(),
            decide(false, PointerType.Touch, PointerEventType.Press, inContact = true, onBar = true),
        )
    }

    @Test
    fun aTapOutsideTheShownBarHidesIt() {
        assertEquals(
            OverlayUpdate(false, OverlayAutoHide.Keep),
            decide(true, PointerType.Touch, PointerEventType.Press, inContact = true, onBar = false),
        )
    }

    @Test
    fun aTapOnTheShownBarKeepsItAndRestartsAutoHide() {
        assertEquals(restart(), decide(true, PointerType.Touch, PointerEventType.Press, inContact = true, onBar = true))
    }

    @Test
    fun touchMovesAndReleasesLeaveItAsIs() {
        assertEquals(keep(), decide(true, PointerType.Touch, PointerEventType.Move, inContact = true, onBar = false))
        assertEquals(
            keep(),
            decide(false, PointerType.Touch, PointerEventType.Release, inContact = false, onBar = true),
        )
    }

    @Test
    fun aHoveringPenFollowsTheTopEdge() {
        assertEquals(
            cancel(true),
            decide(false, PointerType.Stylus, PointerEventType.Move, inContact = false, onBar = true),
        )
        assertEquals(
            cancel(false),
            decide(true, PointerType.Eraser, PointerEventType.Move, inContact = false, onBar = false),
        )
    }

    @Test
    fun aPenInContactLeavesItAsIs() {
        assertEquals(keep(), decide(false, PointerType.Stylus, PointerEventType.Press, inContact = true, onBar = false))
        assertEquals(keep(), decide(true, PointerType.Stylus, PointerEventType.Move, inContact = true, onBar = false))
    }

    private fun decide(
        visible: Boolean,
        pointer: PointerType,
        event: PointerEventType,
        inContact: Boolean,
        onBar: Boolean,
    ) = fullscreenOverlayUpdate(
        visible = visible,
        pointer = pointer,
        event = event,
        inContact = inContact,
        onBar = onBar,
    )

    private fun cancel(visible: Boolean) = OverlayUpdate(visible, OverlayAutoHide.Cancel)

    private fun restart() = OverlayUpdate(true, OverlayAutoHide.Restart)

    private fun keep() = OverlayUpdate(null, OverlayAutoHide.Keep)
}
