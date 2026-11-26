package dev.nucleusframework.window.tao.scene

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.scene.ComposeScenePointer
import dev.nucleusframework.window.tao.TaoTouchEvent

/**
 * The active touch contacts of a window, fed one finger update at a time by
 * `EventCallback.onTouchInput` (Windows, and macOS direct touch since #892).
 * Compose wants the full pointer list with every event and treats a missing
 * pointer as released, so the host accumulates the set here and sends
 * [pointers] after each [update].
 *
 * A pen comes through the same path on Windows (#827), tagged Stylus or
 * Eraser. Unlike a finger it hovers: a Move without a contact is an unpressed
 * pointer, a lift keeps it as one, and only leaving (CANCEL while unpressed)
 * takes it out, as an Exit.
 */
internal class TaoTouchContacts {
    private class Contact(
        val id: Long,
        var xPx: Float,
        var yPx: Float,
        var pressed: Boolean,
        var pressure: Float,
        var type: PointerType,
    )

    /** Insertion order matters for stable pointer ordering across events. */
    private val contacts = LinkedHashMap<Long, Contact>()

    val isEmpty: Boolean get() = contacts.isEmpty()

    /** Whether a pen (or its eraser end) is in contact. */
    val penPressed: Boolean get() = contacts.values.any { it.pressed && it.type != PointerType.Touch }

    /**
     * Applies one touch or pen update and returns the Compose event it makes,
     * or null when it makes none. [onPress] runs before a contact lands.
     */
    @Suppress("LongParameterList")
    fun update(
        phase: Int,
        id: Long,
        xPx: Float,
        yPx: Float,
        pressure: Float,
        type: PointerType,
        onPress: () -> Unit = {},
    ): PointerEventType? =
        when (phase) {
            TaoTouchEvent.PRESS -> {
                onPress()
                contacts[id] = Contact(id, xPx, yPx, pressed = true, pressure, type)
                PointerEventType.Press
            }
            TaoTouchEvent.MOVE -> {
                val existing = contacts[id]
                if (existing != null) {
                    existing.xPx = xPx
                    existing.yPx = yPx
                    existing.pressure = pressure
                    existing.type = type
                    PointerEventType.Move
                } else if (type != PointerType.Touch) {
                    // A pen coming into hover range.
                    contacts[id] = Contact(id, xPx, yPx, pressed = false, pressure, type)
                    PointerEventType.Move
                } else {
                    // Synthetic Press for an unknown id - defensive in case the
                    // platform ever forwards a Move without a prior press
                    // (palm-reject race observed on some Surface drivers).
                    onPress()
                    contacts[id] = Contact(id, xPx, yPx, pressed = true, pressure, type)
                    PointerEventType.Press
                }
            }
            TaoTouchEvent.RELEASE, TaoTouchEvent.CANCEL -> {
                contacts[id]?.let { existing ->
                    val wasPressed = existing.pressed
                    existing.xPx = xPx
                    existing.yPx = yPx
                    existing.pressed = false
                    existing.type = type
                    when {
                        wasPressed -> PointerEventType.Release
                        // A hovering pen leaving; a lifted one sends no second release.
                        phase == TaoTouchEvent.CANCEL -> PointerEventType.Exit
                        else -> null
                    }
                }
            }
            else -> null
        }

    /** Every contact, as the pointer list of the next scene event. */
    @OptIn(InternalComposeUiApi::class, ExperimentalComposeUiApi::class)
    fun pointers(): List<ComposeScenePointer> =
        contacts.values.map { c ->
            ComposeScenePointer(
                id = PointerId(c.id),
                position = Offset(c.xPx, c.yPx),
                pressed = c.pressed,
                type = c.type,
                pressure = c.pressure,
            )
        }

    /**
     * Forgets [id] once its last event went out, so the scene saw the lifted
     * finger one last time with `pressed = false`. A lifted pen stays,
     * hovering, until it leaves range.
     */
    fun purgeAfterDispatch(
        phase: Int,
        id: Long,
    ) {
        val contact = contacts[id] ?: return
        if (phase == TaoTouchEvent.CANCEL || (phase == TaoTouchEvent.RELEASE && contact.type == PointerType.Touch)) {
            contacts.remove(id)
        }
    }

    /** Drops every contact (the window lost them all at once). */
    fun clear() {
        contacts.clear()
    }
}
