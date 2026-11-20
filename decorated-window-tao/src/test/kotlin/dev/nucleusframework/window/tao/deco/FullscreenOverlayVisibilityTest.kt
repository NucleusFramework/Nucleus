package dev.nucleusframework.window.tao.deco

import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import kotlin.test.Test
import kotlin.test.assertEquals

class FullscreenOverlayVisibilityTest {
    @Test
    fun mouseFollowsTheTopEdgeAndCancelsAutoHide() {
        assertEquals(cancel(true), decide(false, PointerType.Mouse, PointerEventType.Move, onBar = true))
        assertEquals(cancel(false), decide(true, PointerType.Mouse, PointerEventType.Move, onBar = false))
        assertEquals(cancel(false), decide(true, PointerType.Mouse, PointerEventType.Press, onBar = false))
    }

    @Test
    fun aTapAnywhereRevealsTheHiddenBar() {
        assertEquals(restart(), decide(false, PointerType.Touch, PointerEventType.Press, onBar = false))
        assertEquals(restart(), decide(false, PointerType.Touch, PointerEventType.Press, onBar = true))
    }

    @Test
    fun aTapOutsideTheShownBarHidesIt() {
        assertEquals(
            OverlayUpdate(false, OverlayAutoHide.Keep),
            decide(true, PointerType.Touch, PointerEventType.Press, onBar = false),
        )
    }

    @Test
    fun aTapOnTheShownBarKeepsItAndRestartsAutoHide() {
        assertEquals(restart(), decide(true, PointerType.Touch, PointerEventType.Press, onBar = true))
    }

    @Test
    fun touchMovesAndReleasesLeaveItAsIs() {
        assertEquals(keep(), decide(true, PointerType.Touch, PointerEventType.Move, onBar = false))
        assertEquals(keep(), decide(false, PointerType.Touch, PointerEventType.Release, onBar = true))
    }

    @Test
    fun unknownPointersHover() {
        assertEquals(cancel(true), decide(false, PointerType.Unknown, PointerEventType.Move, onBar = true))
        assertEquals(cancel(false), decide(true, PointerType.Unknown, PointerEventType.Press, onBar = false))
    }

    private fun decide(
        visible: Boolean,
        pointer: PointerType,
        event: PointerEventType,
        onBar: Boolean,
    ) = fullscreenOverlayUpdate(visible = visible, pointer = pointer, event = event, onBar = onBar)

    private fun cancel(visible: Boolean) = OverlayUpdate(visible, OverlayAutoHide.Cancel)

    private fun restart() = OverlayUpdate(true, OverlayAutoHide.Restart)

    private fun keep() = OverlayUpdate(null, OverlayAutoHide.Keep)
}
