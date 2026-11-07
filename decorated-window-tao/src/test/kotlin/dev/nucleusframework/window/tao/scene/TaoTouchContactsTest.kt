package dev.nucleusframework.window.tao.scene

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import dev.nucleusframework.window.tao.TaoTouchEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(InternalComposeUiApi::class, ExperimentalComposeUiApi::class)
class TaoTouchContactsTest {
    @Test
    fun everyContactIsListedUntilItsReleaseWentOut() {
        val touches = TaoTouchContacts()
        var presses = 0
        assertEquals(
            PointerEventType.Press,
            touches.update(TaoTouchEvent.PRESS, 1, 10f, 10f, 1f, PointerType.Touch) {
                presses++
            },
        )
        assertEquals(
            PointerEventType.Press,
            touches.update(TaoTouchEvent.PRESS, 2, 50f, 10f, 1f, PointerType.Touch) {
                presses++
            },
        )
        assertEquals(2, presses)
        assertEquals(PointerEventType.Move, touches.update(TaoTouchEvent.MOVE, 1, 12f, 10f, 1f, PointerType.Touch))
        assertEquals(listOf(1L, 2L), touches.pointers().map { it.id.value })

        assertEquals(
            PointerEventType.Release,
            touches.update(TaoTouchEvent.RELEASE, 1, 12f, 10f, 1f, PointerType.Touch),
        )
        // The released finger is still in this event, unpressed; the other stays down.
        assertEquals(listOf(false, true), touches.pointers().map { it.pressed })
        touches.purgeAfterDispatch(TaoTouchEvent.RELEASE, 1)
        assertEquals(listOf(2L), touches.pointers().map { it.id.value })
    }

    @Test
    fun aMoveForAnUnknownFingerPressesIt() {
        val touches = TaoTouchContacts()
        var presses = 0
        assertEquals(
            PointerEventType.Press,
            touches.update(TaoTouchEvent.MOVE, 7, 1f, 1f, 1f, PointerType.Touch) { presses++ },
        )
        assertEquals(1, presses)
    }

    @Test
    fun aReleaseOrCancelForAnUnknownContactMakesNoEvent() {
        val touches = TaoTouchContacts()
        assertNull(touches.update(TaoTouchEvent.RELEASE, 3, 1f, 1f, 1f, PointerType.Touch))
        assertNull(touches.update(TaoTouchEvent.CANCEL, 3, 1f, 1f, 1f, PointerType.Touch))
        assertTrue(touches.isEmpty)
    }

    @Test
    fun aPenHoversStaysAfterItsLiftAndExitsOnCancel() {
        val touches = TaoTouchContacts()
        assertEquals(PointerEventType.Move, touches.update(TaoTouchEvent.MOVE, 9, 1f, 1f, 0f, PointerType.Stylus))
        assertEquals(listOf(false), touches.pointers().map { it.pressed })
        assertEquals(PointerEventType.Press, touches.update(TaoTouchEvent.PRESS, 9, 1f, 1f, 0.5f, PointerType.Stylus))
        assertTrue(touches.penPressed)
        assertEquals(PointerEventType.Release, touches.update(TaoTouchEvent.RELEASE, 9, 1f, 1f, 0f, PointerType.Stylus))
        touches.purgeAfterDispatch(TaoTouchEvent.RELEASE, 9)
        // Lifted, the pen hovers on: no second release, an Exit when it leaves.
        assertEquals(listOf(9L), touches.pointers().map { it.id.value })
        assertNull(touches.update(TaoTouchEvent.RELEASE, 9, 1f, 1f, 0f, PointerType.Stylus))
        assertEquals(PointerEventType.Exit, touches.update(TaoTouchEvent.CANCEL, 9, 1f, 1f, 0f, PointerType.Stylus))
        touches.purgeAfterDispatch(TaoTouchEvent.CANCEL, 9)
        assertTrue(touches.isEmpty)
    }
}
