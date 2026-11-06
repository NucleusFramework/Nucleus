package dev.nucleusframework.window.tao.headful

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import dev.nucleusframework.core.runtime.Platform
import dev.nucleusframework.window.tao.ffi.NativeTaoBridge
import dev.nucleusframework.window.tao.ffi.NativeTaoWindowsNativeViewBridge
import java.util.Collections

/**
 * #827 on Windows: a pen reaches the window as `WM_POINTER*` with
 * `pointerType == PT_PEN`, which Tao used to forward as a finger — so its
 * hover even became a touch *press*. Its hover must reach Compose as an
 * unpressed [PointerType.Stylus], its contact as a pressed one, and its
 * eraser end as [PointerType.Eraser]. Real pointer messages from a synthetic
 * `PT_PEN` device ([NativeTaoWindowsNativeViewBridge.nativeDiagInjectPen]),
 * so the system's hit test, Tao's WM_POINTER handler, the JNI loop and the
 * scene host all run.
 *
 * The system cursor follows the pen, so the window also sees plain mouse
 * moves (`PointerType.Mouse` Enter / Exit), never a mouse press. Compose
 * Desktop tracks hover for the mouse only, so leaving range shows no Exit.
 */
internal object WindowsPenPointerTypeHeadfulCases {
    fun all(): List<TaoWindowTestCase> = listOf(penHoverAndContactReachComposeAsTheirTool())

    private data class Recorded(
        val type: PointerEventType,
        val pointer: PointerType,
        val pressed: Boolean,
    )

    private fun penHoverAndContactReachComposeAsTheirTool(): TaoWindowTestCase {
        val recorded = Collections.synchronizedList(mutableListOf<Recorded>())
        return TaoWindowTestCase(
            name = "#827 Windows pen and eraser reach Compose as Stylus and Eraser",
            skip = {
                when {
                    Platform.Current != Platform.Windows -> "Windows only — PT_PEN pointer messages"
                    !NativeTaoWindowsNativeViewBridge.isLoaded -> "nucleus_tao_windows_native_view not loaded"
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
                                event.changes.forEach { recorded += Recorded(event.type, it.type, it.pressed) }
                            }
                        }
                    },
                )
            },
        ) {
            awaitUntil("window mapped") { bounds() != null }
            window.focus()
            settle()
            val hwnd = NativeTaoBridge.nativeHwndHandle(window.handle)

            for ((tool, eraser) in listOf(PointerType.Stylus to false, PointerType.Eraser to true)) {
                stroke(hwnd, eraser, x = if (eraser) 400 else 300)
                awaitUntil("$tool stroke recorded", detail = { "recorded=$recorded" }) {
                    synchronized(recorded) {
                        recorded.any { it.pointer == tool && it.type == PointerEventType.Release }
                    }
                }
            }
            settle()

            val all = synchronized(recorded) { recorded.toList() }
            check(all.none { it.pointer == PointerType.Mouse && it.pressed }) {
                "the pen must not press the mouse; recorded=$all"
            }
            check(all.none { it.pointer == PointerType.Touch }) { "the pen must not be a finger; recorded=$all" }
            val events = all.filter { it.pointer != PointerType.Mouse }
            val lastStylus = events.indexOfLast { it.pointer == PointerType.Stylus }
            check(lastStylus < events.indexOfFirst { it.pointer == PointerType.Eraser }) {
                "the pen stroke must stay Stylus until the eraser comes; recorded=$all"
            }
            for (tool in listOf(PointerType.Stylus, PointerType.Eraser)) {
                val stroke = events.filter { it.pointer == tool }
                val press = stroke.indexOfFirst { it.type == PointerEventType.Press }
                check(press > 0) { "$tool must press once it touches; recorded=$all" }
                check(stroke.take(press).all { it.type == PointerEventType.Move && !it.pressed }) {
                    "a hovering $tool must move unpressed; recorded=$all"
                }
                val release = stroke.indexOfFirst { it.type == PointerEventType.Release }
                check(release > press && stroke.subList(press, release).all { it.pressed }) {
                    "$tool must stay pressed until it lifts; recorded=$all"
                }
                check(stroke.drop(release + 1).let { hover -> hover.isNotEmpty() && hover.none { it.pressed } }) {
                    "$tool must hover again after lifting; recorded=$all"
                }
            }
        }
    }

    /** Hover in, touch down, drag, lift, hover, leave range — what a pen digitizer reports. */
    private suspend fun TaoWindowTestScope.stroke(
        hwnd: Long,
        eraser: Boolean,
        x: Int,
    ) {
        inject(hwnd, Action.HOVER, eraser, x, Y)
        inject(hwnd, Action.HOVER, eraser, x + STEP, Y)
        settle(STEP_MILLIS)
        inject(hwnd, Action.DOWN, eraser, x + STEP, Y)
        inject(hwnd, Action.DRAG, eraser, x + 2 * STEP, Y + STEP)
        settle(STEP_MILLIS)
        inject(hwnd, Action.UP, eraser, x + 2 * STEP, Y + STEP)
        inject(hwnd, Action.HOVER, eraser, x + 3 * STEP, Y + STEP)
        settle(STEP_MILLIS)
        inject(hwnd, Action.LEAVE, eraser, x + 3 * STEP, Y + STEP)
        settle(STEP_MILLIS)
    }

    private fun inject(
        hwnd: Long,
        action: Int,
        eraser: Boolean,
        x: Int,
        y: Int,
    ) {
        val delivered = NativeTaoWindowsNativeViewBridge.nativeDiagInjectPen(hwnd, action, eraser, x, y)
        check(delivered) { "pen input (action=$action, eraser=$eraser) not injected — NUCLEUS_TAO_INPUT_INJECTION=1?" }
    }

    /** `nativeDiagInjectPen` actions. */
    private object Action {
        const val HOVER = 0
        const val DOWN = 1
        const val DRAG = 2
        const val UP = 3
        const val LEAVE = 4
    }

    /** Client px, clear of the title bar and the resize band. */
    private const val Y = 300
    private const val STEP = 20
    private const val STEP_MILLIS = 50L
}
