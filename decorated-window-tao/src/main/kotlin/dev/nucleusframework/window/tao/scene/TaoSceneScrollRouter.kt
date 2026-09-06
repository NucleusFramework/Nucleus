package dev.nucleusframework.window.tao.scene

import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerKeyboardModifiers
import androidx.compose.ui.scene.ComposeScene
import dev.nucleusframework.window.tao.TaoNonFatalCoroutineExceptionHandler
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
import java.util.logging.Logger

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
 * Every step of one pan, the deferred `PanEnd` included, is dispatched at the
 * position and with the modifiers of the last gesture step, deliberately:
 * Compose hit-tests Pan events, and the node that received the `PanMove`s is
 * the one whose scroll session has to be closed — a `PanEnd` sent where the
 * pointer moved to in the meantime would leave it open. A click or a wheel
 * notch while a pan is open closes it first ([finishPan]).
 *
 * Pan events are what Compose's `Modifier.scrollable` consumes for trackpads
 * and what lets a map bind panning and zooming to different gestures. Code
 * that only listens to `PointerEventType.Scroll` no longer sees trackpad
 * input on this backend; until it handles Pan (see `PointerInputChange.panOffset`),
 * `-Dnucleus.tao.trackpadPanEvents=false` restores the AWT-style behaviour
 * where every gesture step is a `Scroll`.
 *
 * [schedule] is only supplied by tests; production routers lazily own a
 * coroutine scope on the UI dispatcher for the end timer. UI thread only.
 */
@OptIn(InternalComposeUiApi::class)
internal class TaoSceneScrollRouter(
    private val target: Target,
    schedule: ((delayMillis: Long, action: () -> Unit) -> (() -> Unit))? = null,
    private val panEnabled: Boolean = trackpadPanEventsEnabled,
    clock: () -> Long = { System.nanoTime() / NANOS_PER_MILLI },
) {
    /** What the router needs from its host, read live at dispatch time. */
    interface Target {
        val scene: ComposeScene?

        /** Px per dp of the scene, for the pan offset. */
        val scale: Float

        /**
         * Wraps the deferred `PanEnd` delivery. Hosts route it through their
         * window exception handler / frame pump; whatever escapes is logged by
         * [TaoNonFatalCoroutineExceptionHandler] — a broken PanEnd costs one
         * gesture, not the app, exactly like the synchronous popup path where
         * `popup_panel.m` clears the pending JNI exception.
         */
        fun guard(block: () -> Unit) = block()
    }

    private val testSchedule = schedule

    // Created on the first deferred end: most popup layers never see a
    // trackpad gesture and must not pay for a scope each.
    private var scope: CoroutineScope? = null

    private fun timerScope(): CoroutineScope =
        scope ?: CoroutineScope(TaoMainDispatcher + SupervisorJob() + TaoNonFatalCoroutineExceptionHandler)
            .also { scope = it }

    private val pan =
        TaoTrackpadPanRouter(
            schedule = testSchedule ?: ::scheduleOnMain,
            send = ::sendPan,
            clock = clock,
        )

    private var cancelled = false

    // Where the pan is, in scene px, plus the modifiers of its last step.
    private var x = 0f
    private var y = 0f
    private var keyboardModifiers = PointerKeyboardModifiers()

    fun onScroll(
        x: Float,
        y: Float,
        event: TaoPointerScrollEvent,
        keyboardModifiers: PointerKeyboardModifiers = PointerKeyboardModifiers(),
    ) {
        if (cancelled) return
        val phase = event.gesturePhase
        if (panEnabled && phase != null) {
            // MayBegin belongs to the NEXT gesture: it closes the previous pan,
            // whose PanEnd must land where that pan's moves went, so the
            // position is not moved for it.
            if (phase != TaoScrollGesturePhase.MAY_BEGIN) {
                this.x = x
                this.y = y
                this.keyboardModifiers = keyboardModifiers
            }
            if (!panAnnounced) {
                // A racing duplicate line is harmless; a CAS per step is not free.
                panAnnounced = true
                logger.config {
                    "Trackpad gestures reach Compose as Pan events (PanStart / PanMove / PanEnd); " +
                        "handlers listening only for PointerEventType.Scroll do not see them. " +
                        "-Dnucleus.tao.trackpadPanEvents=false restores AWT-style Scroll events."
                }
            }
            val orphanedMomentum = !pan.onGesture(phase, Offset(event.dxAwt, event.dyAwt))
            if (orphanedMomentum && (event.dxAwt != 0f || event.dyAwt != 0f)) {
                // An orphaned momentum step (the grace closed the pan before
                // AppKit's tail arrived): Compose is flinging on its own, so a
                // second pan would stack on it — but dropping the tail would
                // stop a flick dead. Deliver it as the wheel scroll it would
                // have been under AWT; the wheel logic interrupts the fling
                // and carries the distance. A zero-delta tail end is skipped,
                // as AWT skips zero deltas.
                target.scene?.dispatchAwtShapedScroll(x, y, event, keyboardModifiers)
            }
        } else {
            // A different device took over: close the pan where it was.
            pan.finishNow()
            target.scene?.dispatchAwtShapedScroll(x, y, event, keyboardModifiers)
        }
    }

    /** Closes an open pan now — a pointer press ends the gesture for Compose too. */
    fun finishPan() {
        if (cancelled) return
        pan.finishNow()
    }

    /** Teardown: drops the pending end, the timer scope, and ignores anything that still arrives. */
    fun cancel() {
        cancelled = true
        pan.cancel()
        scope?.cancel()
        scope = null
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
            timerScope().launch {
                delay(delayMillis)
                target.guard(action)
            }
        return { job.cancel() }
    }

    internal companion object {
        private val logger = Logger.getLogger(TaoSceneScrollRouter::class.java.name)
        private const val NANOS_PER_MILLI = 1_000_000L

        /** One CONFIG line per process the first time a gesture is routed as Pan. */
        @Volatile
        private var panAnnounced = false

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
