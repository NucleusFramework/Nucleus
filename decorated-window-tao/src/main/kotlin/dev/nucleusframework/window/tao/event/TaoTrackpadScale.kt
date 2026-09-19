package dev.nucleusframework.window.tao.event

import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerKeyboardModifiers
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.scene.ComposeScene

/**
 * Feeds one step of a platform-recognized pinch into the scene as Compose's
 * `ScaleStart` / `ScaleChange` / `ScaleEnd` (#660). [scaleFactor] is a
 * multiplicative per-event ratio (`1f` = no change, `> 1f` zoom in, `< 1f`
 * zoom out) — the same shape as `NSEvent.magnification` after `1 + delta`,
 * and as GDK's per-event pinch ratio. Foundation's `transformable` and
 * apps that listen for `PointerEventType.Scale*` consume it directly, so
 * unlike the previous two-finger Touch synthesis there is no second pass
 * through touch slop, span thresholds or release momentum.
 */
@OptIn(InternalComposeUiApi::class)
internal fun ComposeScene.dispatchTrackpadScale(
    x: Float,
    y: Float,
    type: PointerEventType,
    scaleFactor: Float,
    keyboardModifiers: PointerKeyboardModifiers = PointerKeyboardModifiers(),
) {
    sendPointerEvent(
        eventType = type,
        position = Offset(x, y),
        type = PointerType.Mouse,
        keyboardModifiers = keyboardModifiers,
        scaleGestureFactor = scaleFactor,
    )
}

/**
 * Open/move/close a Compose scale gesture from a platform pinch stream
 * (`TaoTrackpadPhase` on macOS/Linux, a debounced tick stream on
 * Windows / Linux Ctrl+wheel). UI thread only.
 */
internal class TaoTrackpadScaleSession(
    private val send: (type: PointerEventType, scaleFactor: Float) -> Unit,
) {
    var active: Boolean = false
        private set

    /** Opens the scale gesture if it is not already open. */
    fun start() {
        if (active) return
        active = true
        send(PointerEventType.ScaleStart, 1f)
    }

    /**
     * Opens the gesture if needed and reports a multiplicative [scaleFactor].
     * A `1f` factor is not a move (Began / Ended ticks, a zero wheel delta).
     */
    fun change(scaleFactor: Float) {
        if (scaleFactor == 1f) return
        start()
        send(PointerEventType.ScaleChange, scaleFactor)
    }

    /**
     * [delta] is `NSEvent.magnification` / GDK's equivalent: the next factor
     * is `1 + delta`, floored so a collapse cannot invert the scale.
     */
    fun magnifyBy(delta: Float) {
        change((1f + delta).coerceAtLeast(MIN_GESTURE_SCALE))
    }

    /** One-shot smart-magnify: a discrete zoom step, then the gesture closes. */
    fun smartMagnify() {
        start()
        change(SMART_MAGNIFY_FACTOR)
        end()
    }

    fun end() {
        if (!active) return
        active = false
        send(PointerEventType.ScaleEnd, 1f)
    }

    internal companion object {
        const val SMART_MAGNIFY_FACTOR: Float = 1.5f
        const val MIN_GESTURE_SCALE: Float = 0.05f
    }
}
