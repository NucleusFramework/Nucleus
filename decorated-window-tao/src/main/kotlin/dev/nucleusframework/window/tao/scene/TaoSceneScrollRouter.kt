package dev.nucleusframework.window.tao.scene

import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerKeyboardModifiers
import androidx.compose.ui.scene.ComposeScene
import dev.nucleusframework.window.tao.TaoPointerScrollEvent
import dev.nucleusframework.window.tao.TaoScrollGesturePhase
import dev.nucleusframework.window.tao.dispatch.TaoMainDispatcher
import dev.nucleusframework.window.tao.event.AWT_PIXEL_TO_ROTATION
import dev.nucleusframework.window.tao.event.dispatchAwtShapedScroll
import dev.nucleusframework.window.tao.event.dispatchTrackpadPan
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Single front door for wheel and trackpad input into a [ComposeScene],
 * shared by the macOS window host and both NSPanel popup hosts so a
 * two-finger swipe behaves the same over a popup list and the window behind it.
 *
 * - A wheel notch or a phase-less precise scroll (smooth-scroll mice) becomes
 *   an AWT-shaped `Scroll` event ([dispatchAwtShapedScroll]).
 * - A trackpad gesture step ([TaoPointerScrollEvent.gesturePhase] set) goes
 *   through [TaoTrackpadPanRouter] and reaches Compose as `PanStart` /
 *   `PanMove` / `PanEnd` (#654), the offset converted from AWT wheel units to
 *   pixels at `10.dp` per unit — the factor Compose Desktop's
 *   `MacOSCocoaConfig` applies to a wheel notch, so a pan and a notch move
 *   content by the same distance, as they do under AWT.
 *
 * Pan events are what Compose's `Modifier.scrollable` consumes for trackpads
 * and what lets a map bind panning and zooming to different gestures. Code
 * that only listens to `PointerEventType.Scroll` no longer sees trackpad
 * input on this backend; until it handles Pan (see `PointerInputChange.panOffset`),
 * `-Dnucleus.tao.trackpadPanEvents=false` restores the AWT-style behaviour
 * where every gesture step is a `Scroll`.
 *
 * [schedule] is only supplied by tests; production routers own a coroutine
 * scope on the UI dispatcher for the deferred `PanEnd`. UI thread only.
 */
@OptIn(InternalComposeUiApi::class)
internal class TaoSceneScrollRouter(
    private val target: Target,
    schedule: ((delayMillis: Long, action: () -> Unit) -> (() -> Unit))? = null,
    private val panEnabled: Boolean = trackpadPanEventsEnabled,
) {
    /** What the router needs from its host, read live at dispatch time. */
    interface Target {
        val scene: ComposeScene?

        /** Px per dp of the scene, for the pan offset. */
        val scale: Float

        /** Wraps the deferred `PanEnd` delivery (exception handler, frame pump). */
        fun guard(block: () -> Unit) = block()
    }

    private val scope: CoroutineScope? =
        if (schedule == null) CoroutineScope(TaoMainDispatcher + SupervisorJob()) else null

    private val pan =
        TaoTrackpadPanRouter(
            schedule = schedule ?: ::scheduleOnMain,
            send = ::sendPan,
        )

    // Where the pan is, in scene px, plus the modifiers of the last step; the
    // deferred PanEnd has no event of its own to read them from.
    private var x = 0f
    private var y = 0f
    private var keyboardModifiers = PointerKeyboardModifiers()

    fun onScroll(
        x: Float,
        y: Float,
        event: TaoPointerScrollEvent,
        keyboardModifiers: PointerKeyboardModifiers = PointerKeyboardModifiers(),
    ) {
        this.x = x
        this.y = y
        this.keyboardModifiers = keyboardModifiers
        if (panEnabled && event.gesturePhase != TaoScrollGesturePhase.NONE) {
            pan.onGesture(event.gesturePhase, Offset(event.dxAwt, event.dyAwt))
        } else {
            target.scene?.dispatchAwtShapedScroll(x, y, event, keyboardModifiers)
        }
    }

    /** Teardown: drops the pending deferred end and the timer scope. */
    fun cancel() {
        pan.cancel()
        scope?.cancel()
    }

    private fun sendPan(
        type: PointerEventType,
        panAwt: Offset,
    ) {
        target.scene?.dispatchTrackpadPan(
            x = x,
            y = y,
            type = type,
            panOffset = panAwt * (AWT_PIXEL_TO_ROTATION * target.scale),
            keyboardModifiers = keyboardModifiers,
        )
    }

    private fun scheduleOnMain(
        delayMillis: Long,
        action: () -> Unit,
    ): () -> Unit {
        val job =
            requireNotNull(scope).launch {
                delay(delayMillis)
                target.guard(action)
            }
        return { job.cancel() }
    }

    internal companion object {
        /**
         * `-Dnucleus.tao.trackpadPanEvents=false` sends trackpad gesture steps
         * down the wheel path as AWT-shaped `Scroll` events instead of Compose
         * Pan events — for apps whose custom pointer handlers only know
         * `PointerEventType.Scroll`. Read once.
         */
        val trackpadPanEventsEnabled: Boolean =
            System.getProperty("nucleus.tao.trackpadPanEvents", "true").toBoolean()
    }
}
