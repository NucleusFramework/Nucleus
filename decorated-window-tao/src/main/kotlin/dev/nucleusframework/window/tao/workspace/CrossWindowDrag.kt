package dev.nucleusframework.window.tao.workspace

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.foundation.gestures.drag
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isFinite
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalWindowInfo
import dev.nucleusframework.window.tao.LocalTaoWindow
import dev.nucleusframework.window.tao.TaoPointerIcons
import dev.nucleusframework.window.tao.TaoWindow
import kotlin.math.roundToInt

/**
 * The drag a group is publishing feedback for, if any. One at a time: a new
 * [begin] releases the previous session, so a gesture that was interrupted
 * rather than finished — its pointer input cancelled by a resize, its window
 * dropped from composition — can neither keep stale feedback on screen nor
 * act on a later release.
 *
 * Sessions check [isLive] before acting and [release] themselves when they
 * end or are cancelled; [clearFeedback] then resets whatever the owner
 * publishes (the dragged item, the drop preview, the ghost).
 */
internal class DragController<S : Any>(
    private val clearFeedback: () -> Unit,
) {
    /** The live session, or `null`. */
    var active: S? = null
        private set

    /** Makes [session] the live one, ending whichever was. */
    fun begin(session: S) {
        active?.let(::release)
        active = session
    }

    fun isLive(session: S): Boolean = active === session

    /** Ends [session] if it is the live one; `null` ends whichever is live. Idempotent. */
    fun release(session: S?) {
        if (session != null && active !== session) return
        active = null
        clearFeedback()
    }
}

/**
 * The pointer position, or `null` when it is not a usable screen coordinate.
 *
 * Compose hands out `Offset.Unspecified` (NaN) for a layout that has been
 * detached, and a synthetic or replayed event can carry an infinity. Feeding
 * either into window geometry produces a window at an undefined position, so
 * a drag drops the sample instead.
 */
internal fun Offset.sanitizedOrNull(): Offset? = takeIf { it.isFinite }

/** Physical pixels → an `Int` window coordinate, clamped to a range no screen exceeds. */
internal fun Float.toWindowCoordinate(): Int = roundToInt().coerceIn(-WINDOW_COORDINATE_LIMIT, WINDOW_COORDINATE_LIMIT)

/** Well past any real multi-monitor desktop, well inside `Int` arithmetic. */
private const val WINDOW_COORDINATE_LIMIT = 1_000_000

/** What a [screenDragHandle] gesture drives. Positions are physical screen pixels. */
internal interface ScreenDrag {
    /** The pointer moved. */
    fun update(pointerScreenPx: Offset)

    /** The pointer was released here. */
    fun end(pointerScreenPx: Offset)

    /** The gesture was abandoned: nothing may change. */
    fun cancel()
}

/**
 * Makes this element the grip of a drag resolved in physical *screen* pixels —
 * the coordinate space windows are placed in, and the only one every window
 * the pointer may cross agrees on.
 *
 * A press without movement does nothing, so buttons can sit inside the grip.
 * Once the touch slop is passed, [begin] is asked for the drag with the
 * pointer's screen position; it is then fed every move and the release, or
 * cancelled when the gesture is abandoned — including when this modifier is
 * detached or re-keyed mid-drag (a window resize does that), which no branch
 * of the gesture itself would observe.
 *
 * The press is claimed in the Main pass, which keeps an enclosing title bar
 * from starting the native window move instead (see `Modifier.noWindowDrag`).
 * The pointer shows [idleIcon] over the grip and [draggingIcon] while
 * [isDragging] holds.
 *
 * Pointer events keep arriving while the button is held, with coordinates
 * outside the window if need be: the OS captures the pointer for the pressed
 * window, which is what lets a drag leave one window and land on another.
 *
 * No-op outside a Tao window.
 */
internal fun Modifier.screenDragHandle(
    key: Any?,
    isDragging: () -> Boolean,
    idleIcon: PointerIcon = TaoPointerIcons.Grab,
    draggingIcon: PointerIcon = TaoPointerIcons.Grabbing,
    begin: (window: TaoWindow, pointerScreenPx: Offset) -> ScreenDrag?,
): Modifier =
    composed {
        val window = LocalTaoWindow.current ?: return@composed Modifier
        val containerSize = LocalWindowInfo.current.containerSize
        var coordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
        val currentBegin by rememberUpdatedState(begin)
        Modifier
            .pointerHoverIcon(if (isDragging()) draggingIcon else idleIcon)
            .onGloballyPositioned { coordinates = it }
            .pointerInput(key, window, containerSize) {
                /** Pointer position in this element → physical screen pixels. */
                fun screenPx(local: Offset): Offset? {
                    val inWindow = coordinates?.localToWindow(local) ?: return null
                    val outer = window.outerBoundsPx() ?: return null
                    return clientOriginPx(outer, containerSize) + inWindow
                }
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    // Claimed in the Main pass: the title bar's native drag arms
                    // on an unconsumed press in the Final pass.
                    down.consume()
                    val start =
                        awaitTouchSlopOrCancellation(down.id) { change, _ -> change.consume() }
                            ?: return@awaitEachGesture
                    var pointer = screenPx(start.position) ?: return@awaitEachGesture
                    val session = currentBegin(window, pointer) ?: return@awaitEachGesture
                    try {
                        session.update(pointer)
                        val released =
                            drag(start.id) { change ->
                                change.consume()
                                screenPx(change.position)?.let {
                                    pointer = it
                                    session.update(it)
                                }
                            }
                        if (released) session.end(pointer) else session.cancel()
                    } finally {
                        // The pointer-input coroutine is cancelled whenever this
                        // modifier is re-keyed or detached — a window resize
                        // mid-drag does it — and neither branch above would run.
                        // Without this the feedback would stay on screen for
                        // good. No-op once the session is done.
                        session.cancel()
                    }
                }
            }
    }
