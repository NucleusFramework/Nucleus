package dev.nucleusframework.window.tao.headful

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import dev.nucleusframework.core.runtime.Platform
import java.awt.GraphicsEnvironment
import java.awt.event.InputEvent

/**
 * `TaoWindow.dragWindow()` on Windows after a drag whose OS move loop never
 * started. tao 0.37's #1400 skipped a drag while its `dragging` flag was set,
 * and only `WM_EXITSIZEMOVE` cleared it: one `dragWindow()` with the button
 * already up, or in fullscreen (where `SC_MOVE` is refused), left every later
 * `dragWindow()` doing nothing. The native caption (`HTCAPTION`) is not
 * concerned; `windowDragArea()` and app drag handles are.
 */
internal object WindowsDragWindowHeadfulCases {
    fun all(): List<TaoWindowTestCase> =
        listOf(
            dragAfterAbortedDrag(inFullscreen = false),
            dragAfterAbortedDrag(inFullscreen = true),
        )

    private fun dragAfterAbortedDrag(inFullscreen: Boolean): TaoWindowTestCase =
        TaoWindowTestCase(
            name =
                "windows dragWindow still moves the window after a drag that never started " +
                    if (inFullscreen) "(in fullscreen)" else "(button up)",
            skip = {
                when {
                    Platform.Current != Platform.Windows -> "Windows only: the OS modal move loop"
                    GraphicsEnvironment.isHeadless() -> "no display"
                    else -> HeadfulRobot.unavailableReason?.let { "no input injection: $it" }
                }
            },
            paintDefaultBackground = false,
            content = {
                val window = window
                Box(Modifier.fillMaxSize().background(Color.DarkGray)) {
                    Box(
                        Modifier
                            .align(Alignment.TopStart)
                            .fillMaxWidth()
                            .height(HANDLE_HEIGHT_DP.dp)
                            .background(Color(HANDLE_COLOR))
                            // An app's own drag handle: start the OS move on press.
                            .pointerInput(Unit) {
                                awaitEachGesture {
                                    awaitFirstDown()
                                    window.dragWindow()
                                }
                            },
                    )
                }
            },
        ) {
            awaitUntil("window mapped") { bounds() != null }
            // Nothing else may cover the handle the robot presses.
            window.setAlwaysOnTop(true)
            window.focus()
            settle()

            val control = dragHandle() ?: return@TaoWindowTestCase
            check(control > MIN_MOVE_PX) { "the control drag did not move the window (dx=$control)" }

            if (inFullscreen) {
                window.setFullscreen(true)
                awaitUntil("fullscreen") { window.isFullscreen }
                settle()
                window.dragWindow()
                settle()
                window.setFullscreen(false)
                awaitUntil("left fullscreen") { !window.isFullscreen }
                settle(LEAVE_FULLSCREEN_SETTLE_MILLIS)
                window.focus()
            } else {
                // No button held: the posted WM_NCLBUTTONDOWN starts no move loop.
                window.dragWindow()
            }
            settle()

            val after = dragHandle() ?: return@TaoWindowTestCase
            check(after > MIN_MOVE_PX) {
                "a drag after one that never started did not move the window (dx=$after)"
            }
        }

    /** Robot-drags the handle by [DRAG_DX_PX]; the window's horizontal move, or null without a robot. */
    private suspend fun TaoWindowTestScope.dragHandle(): Long? {
        val before = requireNotNull(bounds())
        val scale = window.scaleFactor.coerceAtLeast(1f)
        val x = (before[0] + GRAB_X_DP * scale).toInt()
        val y = (before[1] + HANDLE_HEIGHT_DP / 2 * scale).toInt()
        HeadfulRobot.inject { robot ->
            robot.mouseMove(x, y)
            Thread.sleep(PAUSE_MILLIS)
            HeadfulRobot.notePress()
            robot.mousePress(InputEvent.BUTTON1_DOWN_MASK)
            for (step in 1..DRAG_STEPS) {
                robot.mouseMove(x + DRAG_DX_PX * step / DRAG_STEPS, y)
                Thread.sleep(STEP_MILLIS)
            }
            Thread.sleep(PAUSE_MILLIS)
            robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK)
            HeadfulRobot.noteRelease()
        } ?: run {
            System.err.println("[drag-window] robot unavailable: ${HeadfulRobot.unavailableReason}")
            return null
        }
        settle(RELEASE_SETTLE_MILLIS)
        return requireNotNull(bounds())[0] - before[0]
    }

    private const val HANDLE_HEIGHT_DP = 56
    private const val HANDLE_COLOR = 0xFF445566
    private const val GRAB_X_DP = 280
    private const val DRAG_DX_PX = 150
    private const val MIN_MOVE_PX = 50
    private const val DRAG_STEPS = 10
    private const val STEP_MILLIS = 40L
    private const val PAUSE_MILLIS = 120L
    private const val RELEASE_SETTLE_MILLIS = 600L
    private const val LEAVE_FULLSCREEN_SETTLE_MILLIS = 1000L
}
