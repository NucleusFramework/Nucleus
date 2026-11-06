package dev.nucleusframework.window.tao.headful

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import dev.nucleusframework.core.runtime.Platform
import dev.nucleusframework.window.tao.TaoEventCode
import dev.nucleusframework.window.tao.TaoMouseButton
import dev.nucleusframework.window.tao.TaoPointerKind
import java.util.Collections

/**
 * #827 on Linux: the tool `events.rs` names in `EVENT_POINTER_KIND` must reach
 * Compose as the pointer type of the cursor and button events that follow —
 * a pen hovers and presses as [PointerType.Stylus], its eraser end as
 * [PointerType.Eraser], and the mouse is a mouse again afterwards. Drives the
 * JNI wire, i.e. what `event_loop.rs` sends for GDK's `GDK_SOURCE_PEN` /
 * `GDK_SOURCE_ERASER` source devices; the GDK side was checked against a
 * uinput tablet on GNOME Wayland.
 */
internal object LinuxPenPointerTypeHeadfulCases {
    fun all(): List<TaoWindowTestCase> = listOf(penHoverAndContactReachComposeAsTheirTool())

    private fun penHoverAndContactReachComposeAsTheirTool(): TaoWindowTestCase {
        val recorded = Collections.synchronizedList(mutableListOf<Pair<PointerEventType, PointerType>>())
        return TaoWindowTestCase(
            name = "#827 Linux pen and eraser reach Compose as Stylus and Eraser",
            skip = { if (Platform.Current != Platform.Linux) "Linux only — GDK tablet source devices" else null },
            paintDefaultBackground = false,
            content = {
                Box(
                    Modifier.fillMaxSize().pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                event.changes.forEach { recorded += event.type to it.type }
                            }
                        }
                    },
                )
            },
        ) {
            awaitUntil("window mapped") { bounds() != null }
            settle()

            stroke(TaoPointerKind.STYLUS, x = 300)
            stroke(TaoPointerKind.ERASER, x = 400)
            stroke(TaoPointerKind.MOUSE, x = 500)
            settle()

            val events = synchronized(recorded) { recorded.toList() }
            val stroke = listOf(PointerEventType.Move, PointerEventType.Press, PointerEventType.Release)
            for (tool in listOf(PointerType.Stylus, PointerType.Eraser, PointerType.Mouse)) {
                val types = events.filter { it.second == tool }.map { it.first }.toSet()
                check(types.containsAll(stroke)) {
                    "$tool must hover, press and release as $tool; recorded=$events"
                }
            }
            val firstStylus = events.indexOfFirst { it.second == PointerType.Stylus }
            val firstEraser = events.indexOfFirst { it.second == PointerType.Eraser }
            check(events.drop(firstStylus).take(firstEraser - firstStylus).all { it.second == PointerType.Stylus }) {
                "every event of the pen stroke must be Stylus; recorded=$events"
            }
        }
    }

    /** Hover in, touch down, drag, lift — the event sequence GDK reports for a tablet tool. */
    private suspend fun TaoWindowTestScope.stroke(
        kind: Int,
        x: Int,
    ) {
        window.dispatch(TaoEventCode.POINTER_KIND, kind, 0)
        moveCursor(x, Y)
        moveCursor(x + STEP, Y)
        settle(STEP_MILLIS)
        window.dispatch(TaoEventCode.MOUSE_DOWN, TaoMouseButton.LEFT, 0)
        moveCursor(x + 2 * STEP, Y + STEP)
        settle(STEP_MILLIS)
        window.dispatch(TaoEventCode.MOUSE_UP, TaoMouseButton.LEFT, 0)
        settle(STEP_MILLIS)
    }

    /** Compose's pointer, through the CURSOR_MOVED wire (content px, 1/1024 fixed point). */
    private fun TaoWindowTestScope.moveCursor(
        x: Int,
        y: Int,
    ) {
        val fixed = window.scaleFactor * CURSOR_FIXED_SCALE
        window.dispatch(TaoEventCode.CURSOR_MOVED, (x * fixed).toInt(), (y * fixed).toInt())
    }

    /** Must match `events.rs::CURSOR_FIXED_SCALE`. */
    private const val CURSOR_FIXED_SCALE = 1024f

    /** Widget-local logical px, clear of the title bar and the resize band. */
    private const val Y = 300
    private const val STEP = 20
    private const val STEP_MILLIS = 50L
}
