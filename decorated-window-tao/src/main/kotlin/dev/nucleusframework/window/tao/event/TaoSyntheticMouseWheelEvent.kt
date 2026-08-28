package dev.nucleusframework.window.tao.event

import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerKeyboardModifiers
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isAltPressed
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isMetaPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.scene.ComposeScene
import dev.nucleusframework.window.tao.TaoPointerScrollEvent
import java.awt.Component
import java.awt.event.InputEvent
import java.awt.event.MouseWheelEvent
import kotlin.math.abs
import kotlin.math.roundToInt

internal object TaoSyntheticMouseWheelEvent {
    // Bare Component, never a Swing component: this `val` initialises on the first
    // scroll ON THE TAO MAIN THREAD (GTK/GLX loop). A `JPanel` would run the Swing
    // L&F / toolkit init there and deadlock the app. See [TaoSyntheticKey].
    private val source: Component = object : Component() {}

    fun create(
        event: TaoPointerScrollEvent,
        x: Float,
        y: Float,
        keyboardModifiers: PointerKeyboardModifiers,
    ): MouseWheelEvent {
        val preciseWheelRotation = event.primaryAxisDelta.toDouble()
        return MouseWheelEvent(
            source,
            MouseWheelEvent.MOUSE_WHEEL,
            System.currentTimeMillis(),
            keyboardModifiers.toAwtModifiersEx(),
            x.roundToInt(),
            y.roundToInt(),
            0,
            0,
            0,
            false,
            MouseWheelEvent.WHEEL_UNIT_SCROLL,
            event.scrollAmount.coerceAtLeast(1),
            preciseWheelRotation.roundToInt(),
            preciseWheelRotation,
        )
    }

    private val TaoPointerScrollEvent.primaryAxisDelta: Float
        get() = if (abs(dxAwt) >= abs(dyAwt)) dxAwt else dyAwt

    private fun PointerKeyboardModifiers.toAwtModifiersEx(): Int =
        (if (isShiftPressed) InputEvent.SHIFT_DOWN_MASK else 0) or
            (if (isCtrlPressed) InputEvent.CTRL_DOWN_MASK else 0) or
            (if (isAltPressed) InputEvent.ALT_DOWN_MASK else 0) or
            (if (isMetaPressed) InputEvent.META_DOWN_MASK else 0)
}

/**
 * Feeds an already AWT-shaped [TaoPointerScrollEvent] into the scene, including
 * the synthetic `MouseWheelEvent` Compose Desktop's scroll config reads for
 * `scrollAmount` / `preciseWheelRotation`.
 *
 * Single Compose entry for wheel input: [dev.nucleusframework.window.tao.TaoWindow]
 * produces the event; popup WndProcs / NSPanel / X11 Button4–7 skip tao and
 * must map first ([win32WheelToAwtScrollEvent], [appKitWheelToAwtScrollEvent],
 * [linuxWheelToAwtScrollEvent]).
 */
@OptIn(InternalComposeUiApi::class)
internal fun ComposeScene.dispatchAwtShapedScroll(
    x: Float,
    y: Float,
    event: TaoPointerScrollEvent,
    keyboardModifiers: PointerKeyboardModifiers = PointerKeyboardModifiers(),
) {
    sendPointerEvent(
        eventType = PointerEventType.Scroll,
        position = Offset(x, y),
        scrollDelta = Offset(event.dxAwt, event.dyAwt),
        type = PointerType.Mouse,
        keyboardModifiers = keyboardModifiers,
        nativeEvent =
            TaoSyntheticMouseWheelEvent.create(
                event = event,
                x = x,
                y = y,
                keyboardModifiers = keyboardModifiers,
            ),
    )
}
