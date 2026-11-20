@file:Suppress("MagicNumber")

package dev.nucleusframework.window.tao.event

import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.scene.ComposeScene
import dev.nucleusframework.core.runtime.Platform
import dev.nucleusframework.window.tao.ffi.TaoNativeWireFormat
import java.awt.Component
import java.awt.event.InputEvent

/**
 * Builds the Compose [KeyEvent] for a physical key press/release.
 *
 * CRITICAL: the event MUST carry a non-null `nativeEvent`. Without it, Compose's
 * `ComposeSceneInputHandler` treats the event as non-system-initiated and, when
 * its modifiers are empty, REPLACES them with its own tracked keyboard-modifier
 * state (`KeyEvent.withTrackedModifiers`). That tracker latches a modifier on its
 * key-down and only clears it on the matching key-up — but an OS hotkey
 * (e.g. Win+Space switching keyboard layout) swallows the modifier key-up, so the
 * tracker stays stuck and every later unmodified key is delivered as
 * Ctrl/Cmd/Alt+<key> (pressing Hebrew א — same physical key as T — opened a new
 * tab). The AWT (JNI) backend never hits this because real key events always
 * carry a nativeEvent; we mirror that so our authoritative modifiers always win.
 */
@OptIn(InternalComposeUiApi::class)
internal fun taoKeyEvent(
    keyDown: Boolean,
    vkCode: Int,
    keyLocation: Int,
    isShift: Boolean,
    isCtrl: Boolean,
    isAlt: Boolean,
    isMeta: Boolean,
    codePoint: Int,
    isRepeat: Boolean = false,
): KeyEvent {
    val awtEvent =
        TaoAwtKeyEvent(
            if (keyDown) java.awt.event.KeyEvent.KEY_PRESSED else java.awt.event.KeyEvent.KEY_RELEASED,
            awtModifierMask(isShift, isCtrl, isAlt, isMeta),
            vkCode,
            java.awt.event.KeyEvent.CHAR_UNDEFINED,
            keyLocation,
            // A release is never a repeat, whatever the caller passed.
            isRepeat = keyDown && isRepeat,
        )
    return KeyEvent(
        key = Key(nativeKeyCode = vkCode, nativeKeyLocation = keyLocation),
        type = if (keyDown) KeyEventType.KeyDown else KeyEventType.KeyUp,
        codePoint = codePoint,
        isShiftPressed = isShift,
        isCtrlPressed = isCtrl,
        isAltPressed = isAlt,
        isMetaPressed = isMeta,
        nativeEvent = awtEvent,
    )
}

/**
 * Builds a Compose [KeyEvent] of type [KeyEventType.Unknown] piggy-backing on a
 * real `java.awt.event.KeyEvent.KEY_TYPED`. Compose Desktop's `BasicTextField`
 * only inserts a character when it sees the AWT KEY_TYPED event nested inside a
 * Compose KeyEvent — that's the gate `KeyEvent.isTypedEvent` checks. Without it,
 * KeyDown alone moves focus / fires onKeyEvent but never produces visible text.
 */
@OptIn(InternalComposeUiApi::class)
internal fun taoTypedKeyEvent(
    codePoint: Int,
    keyLocation: Int,
    isShift: Boolean,
    isCtrl: Boolean,
    isAlt: Boolean,
    isMeta: Boolean,
): KeyEvent {
    val awtEvent =
        TaoAwtKeyEvent(
            java.awt.event.KeyEvent.KEY_TYPED,
            awtModifierMask(isShift, isCtrl, isAlt, isMeta),
            java.awt.event.KeyEvent.VK_UNDEFINED,
            codePoint.toChar(),
            java.awt.event.KeyEvent.KEY_LOCATION_UNKNOWN,
            isRepeat = false,
        )
    return KeyEvent(
        key = Key(nativeKeyCode = 0, nativeKeyLocation = keyLocation),
        type = KeyEventType.Unknown,
        codePoint = codePoint,
        isShiftPressed = isShift,
        isCtrlPressed = isCtrl,
        isAltPressed = isAlt,
        isMetaPressed = isMeta,
        nativeEvent = awtEvent,
    )
}

/**
 * Dispatches a synthetic KEY_TYPED to [this] scene for character insertion.
 * See [taoTypedKeyEvent]. No-op for non-text input (control chars, Cmd/Ctrl combos).
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
    return sendKeyEvent(taoTypedKeyEvent(codePoint, keyLocation = 0, isShift, isCtrl, isAlt, isMeta))
}

private fun awtModifierMask(
    isShift: Boolean,
    isCtrl: Boolean,
    isAlt: Boolean,
    isMeta: Boolean,
): Int =
    (if (isShift) InputEvent.SHIFT_DOWN_MASK else 0) or
        (if (isCtrl) InputEvent.CTRL_DOWN_MASK else 0) or
        (if (isAlt) InputEvent.ALT_DOWN_MASK else 0) or
        (if (isMeta) InputEvent.META_DOWN_MASK else 0)

/**
 * Full key dispatch for a scene fed straight from a native popup/overlay
 * callback (popup layers, standalone panels, native-view overlays):
 * preview handler → scene → synthetic KEY_TYPED insertion on key-down →
 * fallback handler. Window hosts keep their own richer pipeline (modifier
 * tracking, popup handler chains).
 *
 * Native callbacks don't report a key location, and desktop `Key.*`
 * constants are declared with `KEY_LOCATION_STANDARD` — since `Key`
 * equality encodes the location, anything else (e.g. UNKNOWN) makes every
 * non-character key (Backspace, Enter, arrows…) unmatchable.
 *
 * [vkCode] is the platform's native virtual-key code: Windows `VK_*` values
 * match the AWT codes Compose's `Key` constants use, but macOS `NSEvent.keyCode`
 * (`kVK_*`) values and Linux X11 keysyms do not and are translated via
 * [macNativeKeyToAwt] / [linuxNativeKeyToAwt].
 */
@OptIn(InternalComposeUiApi::class)
internal fun ComposeScene.dispatchNativeKeyEvent(
    type: Int,
    vkCode: Int,
    codePoint: Int,
    modifiers: Int,
    isRepeat: Boolean = false,
    onPreviewKeyEvent: ((KeyEvent) -> Boolean)? = null,
    onKeyEvent: ((KeyEvent) -> Boolean)? = null,
) {
    val isShift = modifiers and TaoNativeWireFormat.MOD_SHIFT != 0
    val isCtrl = modifiers and TaoNativeWireFormat.MOD_CTRL != 0
    val isAlt = modifiers and TaoNativeWireFormat.MOD_ALT != 0
    val isMeta = modifiers and TaoNativeWireFormat.MOD_META != 0
    val (awtVkCode, keyLocation) =
        when (Platform.Current) {
            Platform.MacOS -> macNativeKeyToAwt(vkCode, codePoint)
            Platform.Linux -> linuxNativeKeyToAwt(vkCode, codePoint)
            else -> vkCode to java.awt.event.KeyEvent.KEY_LOCATION_STANDARD
        }
    val ev =
        taoKeyEvent(
            keyDown = type == TaoNativeWireFormat.KEY_DOWN,
            vkCode = awtVkCode,
            keyLocation = keyLocation,
            isShift = isShift,
            isCtrl = isCtrl,
            isAlt = isAlt,
            isMeta = isMeta,
            codePoint = codePoint,
            isRepeat = isRepeat,
        )
    if (onPreviewKeyEvent?.invoke(ev) == true) return
    val consumed = sendKeyEvent(ev)
    if (type == TaoNativeWireFormat.KEY_DOWN) {
        dispatchSyntheticKeyTyped(codePoint, isShift, isCtrl, isAlt, isMeta)
    }
    if (consumed) return
    onKeyEvent?.invoke(ev)
}

/**
 * Heuristic: ASCII control range, Cmd/Ctrl combos and Apple's function-key
 * Unicode range are not text input. macOS reports arrows/F-keys/Home… as
 * PUA code points (`NSEvent.characters` = U+F700–U+F8FF) which would
 * otherwise be inserted into text fields as tofu glyphs.
 */
internal fun Int.isPrintableTextInput(
    isCtrl: Boolean,
    isMeta: Boolean,
): Boolean = this >= 0x20 && this != 0x7F && this !in 0xF700..0xF8FF && !isCtrl && !isMeta

/**
 * The AWT event every Nucleus key event carries as its `nativeEvent`, tagged
 * with whether it is an auto-repeat of a held key — which AWT's `KeyEvent`
 * has no field for. Read back by [dev.nucleusframework.window.tao.isRepeat].
 * Only a key-down is ever tagged: on macOS and Linux the typed text reaches
 * Nucleus before the key-down that produced it, so it cannot be matched.
 */
internal class TaoAwtKeyEvent(
    id: Int,
    modifiers: Int,
    keyCode: Int,
    keyChar: Char,
    keyLocation: Int,
    val isRepeat: Boolean,
) : java.awt.event.KeyEvent(
        SyntheticAwtKeyEventSource,
        id,
        System.currentTimeMillis(),
        modifiers,
        keyCode,
        keyChar,
        keyLocation,
    )

/**
 * AWT requires a non-null `Component` as the source of every key event.
 * The instance is never shown and never receives the event back — it's
 * a placeholder so the constructor doesn't NPE.
 *
 * MUST be a bare [Component], NOT a Swing component (e.g. `JPanel`). This `val`
 * is initialised lazily on the first key event, ON THE TAO MAIN THREAD that owns
 * the GTK/GLX event loop. A `JPanel` runs `JComponent.updateUI()` → the GTK Swing
 * Look&Feel (`GTKStyle.nativeGetXThickness`, a GTK/Xlib call) — or, under
 * native-image with the Metal L&F, the `Toolkit`/fontmanager init — re-entering
 * GTK/AWT on the loop thread and DEADLOCKING the whole app on the first keystroke.
 * A bare `Component` has no UI delegate and touches neither the L&F nor the
 * toolkit. See [dev.nucleusframework.window.tao.TaoLinuxUriHandler] for the same
 * "AWT init deadlocks the Tao loop" hazard.
 */
internal val SyntheticAwtKeyEventSource: Component = object : Component() {}
