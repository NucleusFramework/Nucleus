package dev.nucleusframework.window.tao.headful

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import dev.nucleusframework.core.runtime.Platform
import dev.nucleusframework.window.tao.ffi.NativeMetalBridge
import java.util.Collections

/**
 * #827 on macOS: a tablet pen reaches AppKit as a proximity event naming the
 * tool, then as ordinary mouse events whose `subtype` is `TabletPoint`. Its
 * hover and contact must reach Compose as [PointerType.Stylus], its eraser end
 * as [PointerType.Eraser], and the mouse must be a mouse again afterwards.
 * Real NSEvents queued on `NSApp` ([NativeMetalBridge.nativeDiagInjectTabletEvent]),
 * so tao's view, the JNI loop and the scene host all run.
 */
internal object MacOsPenPointerTypeHeadfulCases {
    fun all(): List<TaoWindowTestCase> = listOf(penHoverAndContactReachComposeAsTheirTool())

    private fun penHoverAndContactReachComposeAsTheirTool(): TaoWindowTestCase {
        val recorded = Collections.synchronizedList(mutableListOf<Pair<PointerEventType, PointerType>>())
        return TaoWindowTestCase(
            name = "#827 macOS pen and eraser reach Compose as Stylus and Eraser",
            skip = {
                when {
                    Platform.Current != Platform.MacOS -> "macOS only — AppKit tablet events"
                    !NativeMetalBridge.isLoaded -> "nucleus_tao_metal not loaded"
                    else -> null
                }
            },
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
            window.focus()
            settle()

            stroke(Tool.PEN, x = 300f)
            stroke(Tool.ERASER, x = 400f)
            stroke(Tool.MOUSE, x = 500f)
            awaitUntil("three strokes recorded", detail = { "recorded=$recorded" }) {
                synchronized(recorded) { recorded.count { it.first == PointerEventType.Release } } >= 3
            }
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

    /** Proximity in, hover, touch down, drag, lift, proximity out — what a tablet driver sends. */
    private suspend fun TaoWindowTestScope.stroke(
        tool: Int,
        x: Float,
    ) {
        if (tool != Tool.MOUSE) inject(Action.PROXIMITY_ENTER, tool, x, Y)
        inject(Action.MOVE, tool, x, Y)
        inject(Action.MOVE, tool, x + STEP, Y)
        settle(STEP_MILLIS)
        inject(Action.DOWN, tool, x + STEP, Y)
        inject(Action.DRAG, tool, x + 2 * STEP, Y + STEP)
        settle(STEP_MILLIS)
        inject(Action.UP, tool, x + 2 * STEP, Y + STEP)
        if (tool != Tool.MOUSE) inject(Action.PROXIMITY_LEAVE, tool, x, Y)
        settle(STEP_MILLIS)
    }

    private fun TaoWindowTestScope.inject(
        action: Int,
        tool: Int,
        x: Float,
        y: Float,
    ) {
        val delivered = NativeMetalBridge.nativeDiagInjectTabletEvent(window.nativeHandle, action, tool, x, y)
        check(delivered) { "tablet event (action=$action, tool=$tool) not queued — NUCLEUS_TAO_INPUT_INJECTION=1?" }
    }

    /** `nativeDiagInjectTabletEvent` actions. */
    private object Action {
        const val PROXIMITY_ENTER = 0
        const val PROXIMITY_LEAVE = 1
        const val MOVE = 2
        const val DOWN = 3
        const val DRAG = 4
        const val UP = 5
    }

    /** `nativeDiagInjectTabletEvent` tools. */
    private object Tool {
        const val MOUSE = 0
        const val PEN = 1
        const val ERASER = 2
    }

    /** Content-local points, clear of the title bar and the resize band. */
    private const val Y = 300f
    private const val STEP = 20f
    private const val STEP_MILLIS = 50L
}
