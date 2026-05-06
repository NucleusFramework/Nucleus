package io.github.kdroidfilter.nucleus.window.tao.render

import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.scene.ComposeScene
import java.awt.Component
import java.awt.event.InputEvent
import javax.swing.JPanel

/**
 * Synthesizes a Compose [KeyEvent] of type [KeyEventType.Unknown]
 * piggy-backing on a real `java.awt.event.KeyEvent.KEY_TYPED`. Compose
 * Desktop's `BasicTextField` only inserts a character when it sees the
 * AWT KEY_TYPED event nested inside a Compose KeyEvent — that's the
 * gate `KeyEvent.isTypedEvent` checks.
 *
 * Without this trick, KeyDown alone moves focus / fires onKeyEvent but
 * never produces visible text input.
 */
@OptIn(InternalComposeUiApi::class)
internal fun ComposeScene.dispatchSyntheticKeyTyped(
    codePoint: Int,
    isShift: Boolean,
    isCtrl: Boolean,
    isAlt: Boolean,
    isMeta: Boolean,
): Boolean {
    if (!codePoint.isPrintableTextInput(isCtrl, isMeta)) return false
    val awtModifiers =
        (if (isShift) InputEvent.SHIFT_DOWN_MASK else 0) or
            (if (isCtrl) InputEvent.CTRL_DOWN_MASK else 0) or
            (if (isAlt) InputEvent.ALT_DOWN_MASK else 0) or
            (if (isMeta) InputEvent.META_DOWN_MASK else 0)
    val awtEvent = java.awt.event.KeyEvent(
        SyntheticAwtKeyEventSource,
        java.awt.event.KeyEvent.KEY_TYPED,
        System.currentTimeMillis(),
        awtModifiers,
        java.awt.event.KeyEvent.VK_UNDEFINED,
        codePoint.toChar(),
        java.awt.event.KeyEvent.KEY_LOCATION_UNKNOWN,
    )
    return sendKeyEvent(
        KeyEvent(
            key = Key(nativeKeyCode = 0, nativeKeyLocation = 0),
            type = KeyEventType.Unknown,
            codePoint = codePoint,
            isShiftPressed = isShift,
            isCtrlPressed = isCtrl,
            isAltPressed = isAlt,
            isMetaPressed = isMeta,
            nativeEvent = awtEvent,
        ),
    )
}

/** Heuristic: ASCII control range and Cmd/Ctrl combos are not text input. */
internal fun Int.isPrintableTextInput(isCtrl: Boolean, isMeta: Boolean): Boolean =
    this >= 0x20 && this != 0x7F && !isCtrl && !isMeta

/**
 * AWT requires a non-null `Component` as the source of every key event.
 * The instance is never shown and never receives the event back — it's
 * a placeholder so the constructor doesn't NPE.
 */
internal val SyntheticAwtKeyEventSource: Component = JPanel()
