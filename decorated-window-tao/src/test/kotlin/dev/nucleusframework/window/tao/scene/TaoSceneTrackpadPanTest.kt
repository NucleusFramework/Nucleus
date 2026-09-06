package dev.nucleusframework.window.tao.scene

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import dev.nucleusframework.window.tao.TaoPointerScrollEvent
import dev.nucleusframework.window.tao.TaoScrollGesturePhase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Stage-1 trackpad pan tests (#654): the pan events the scene host emits for
 * a macOS trackpad gesture (`dispatchTrackpadPan`, mirrored by
 * [TaoSceneTestScope.pan]) drive foundation's `TrackpadScrollingLogic` on
 * real scrollable content, with the AWT sign convention — positive pan =
 * content scrolls down / right — and the `10.dp` per wheel unit magnitude the
 * host derives from `MacOSCocoaConfig`.
 */
class TaoSceneTrackpadPanTest {
    @Test
    fun `positive vertical pan scrolls a column down`() =
        runTaoSceneTest(width = 100, height = 200) {
            val scrollValue = mutableStateOf(0)
            setContent {
                val state = rememberScrollState()
                scrollValue.value = state.value
                Column(Modifier.fillMaxSize().verticalScroll(state)) {
                    repeat(50) { Box(Modifier.fillMaxWidth().height(20.dp)) }
                }
            }
            moveMouse(50f, 100f)
            pan(PointerEventType.PanStart, Offset.Zero)
            repeat(3) { pan(PointerEventType.PanMove, Offset(0f, PAN_STEP_PX)) }
            pan(PointerEventType.PanEnd, Offset.Zero)
            frameUntilIdle()
            assertTrue(scrollValue.value > 0, "pan down must advance the scroll state (got ${scrollValue.value})")
        }

    @Test
    fun `positive horizontal pan scrolls a row forward`() =
        runTaoSceneTest(width = 200, height = 100) {
            val scrollValue = mutableStateOf(0)
            setContent {
                val state = rememberScrollState()
                scrollValue.value = state.value
                Row(Modifier.fillMaxSize().horizontalScroll(state)) {
                    repeat(50) { Box(Modifier.fillMaxHeight().width(20.dp)) }
                }
            }
            moveMouse(100f, 50f)
            pan(PointerEventType.PanStart, Offset.Zero)
            repeat(3) { pan(PointerEventType.PanMove, Offset(PAN_STEP_PX, 0f)) }
            pan(PointerEventType.PanEnd, Offset.Zero)
            frameUntilIdle()
            assertTrue(scrollValue.value > 0, "pan right must advance the scroll state (got ${scrollValue.value})")
        }

    @Test
    fun `negative pan at the origin is a no-op`() =
        runTaoSceneTest(width = 100, height = 200) {
            val scrollValue = mutableStateOf(-1)
            setContent {
                val state = rememberScrollState()
                scrollValue.value = state.value
                Column(Modifier.fillMaxSize().verticalScroll(state)) {
                    repeat(50) { Box(Modifier.fillMaxWidth().height(20.dp)) }
                }
            }
            moveMouse(50f, 100f)
            pan(PointerEventType.PanStart, Offset.Zero)
            pan(PointerEventType.PanMove, Offset(0f, -PAN_STEP_PX))
            pan(PointerEventType.PanEnd, Offset.Zero)
            frameUntilIdle()
            assertEquals(0, scrollValue.value)
        }

    @Test
    fun `pan moves content by its pixel offset`() =
        runTaoSceneTest(width = 100, height = 100) {
            setContent {
                val state = rememberScrollState()
                Column(Modifier.fillMaxSize().verticalScroll(state)) {
                    Box(Modifier.fillMaxWidth().height(100.dp).background(Color.Red))
                    Box(Modifier.fillMaxWidth().height(100.dp).background(Color.Blue))
                }
            }
            assertEquals(RED, pixelAt(50, 50))
            moveMouse(50f, 50f)
            pan(PointerEventType.PanStart, Offset.Zero)
            // 100 px of pan on a 100 px viewport: the blue block must be fully in.
            repeat(5) { pan(PointerEventType.PanMove, Offset(0f, 20f)) }
            pan(PointerEventType.PanEnd, Offset.Zero)
            frameUntilIdle()
            assertEquals(BLUE, pixelAt(50, 50), "after a 100 px pan the blue block must fill the viewport")
        }

    @Test
    fun `routed gesture steps pan a column and close after the grace`() =
        runTaoSceneTest(width = 100, height = 200) {
            val scrollValue = mutableStateOf(0)
            val seen = mutableListOf<PointerEventType>()
            setContent {
                val state = rememberScrollState()
                scrollValue.value = state.value
                Column(Modifier.fillMaxSize().verticalScroll(state).recording(seen)) {
                    repeat(50) { Box(Modifier.fillMaxWidth().height(20.dp)) }
                }
            }
            moveMouse(50f, 100f)
            // Fingers up (AppKit -10 pt → AWT +1) three times, then lift.
            routeScroll(gestureStep(TaoScrollGesturePhase.BEGAN, dyAwt = 0f))
            repeat(3) { routeScroll(gestureStep(TaoScrollGesturePhase.CHANGED, dyAwt = 1f)) }
            routeScroll(gestureStep(TaoScrollGesturePhase.ENDED, dyAwt = 0f))
            frameUntilIdle()
            assertTrue(scrollValue.value > 0, "routed pan must scroll the column (got ${scrollValue.value})")
            assertEquals(
                listOf(
                    PointerEventType.PanStart,
                    PointerEventType.PanMove,
                    PointerEventType.PanMove,
                    PointerEventType.PanMove,
                ),
                seen.toList(),
                "a gesture must reach Compose as Pan, never as Scroll",
            )
            elapsePanGrace()
            assertEquals(PointerEventType.PanEnd, seen.last(), "the deferred PanEnd must close the gesture")
        }

    @Test
    fun `with pan events disabled gesture steps scroll as wheel events`() =
        runTaoSceneTest(width = 100, height = 200) {
            val scrollValue = mutableStateOf(0)
            val seen = mutableListOf<PointerEventType>()
            setContent {
                val state = rememberScrollState()
                scrollValue.value = state.value
                Column(Modifier.fillMaxSize().verticalScroll(state).recording(seen)) {
                    repeat(50) { Box(Modifier.fillMaxWidth().height(20.dp)) }
                }
            }
            moveMouse(50f, 100f)
            routeScroll(gestureStep(TaoScrollGesturePhase.BEGAN, dyAwt = 0f), panEvents = false)
            repeat(3) { routeScroll(gestureStep(TaoScrollGesturePhase.CHANGED, dyAwt = 1f), panEvents = false) }
            routeScroll(gestureStep(TaoScrollGesturePhase.ENDED, dyAwt = 0f), panEvents = false)
            frameUntilIdle()
            assertTrue(scrollValue.value > 0, "legacy routing must still scroll the column (got ${scrollValue.value})")
            assertTrue(
                seen.isNotEmpty() && seen.all { it == PointerEventType.Scroll },
                "expected Scroll only, got $seen",
            )
        }

    @Test
    fun `an orphaned momentum tail scrolls as wheel events instead of stalling`() =
        runTaoSceneTest(width = 100, height = 200) {
            val scrollValue = mutableStateOf(0)
            val seen = mutableListOf<PointerEventType>()
            setContent {
                val state = rememberScrollState()
                scrollValue.value = state.value
                Column(Modifier.fillMaxSize().verticalScroll(state).recording(seen)) {
                    repeat(50) { Box(Modifier.fillMaxWidth().height(20.dp)) }
                }
            }
            moveMouse(50f, 100f)
            routeScroll(gestureStep(TaoScrollGesturePhase.BEGAN, dyAwt = 0f))
            routeScroll(gestureStep(TaoScrollGesturePhase.CHANGED, dyAwt = 1f))
            routeScroll(gestureStep(TaoScrollGesturePhase.ENDED, dyAwt = 0f))
            // The grace fires before AppKit's tail shows up.
            elapsePanGrace()
            frameUntilIdle()
            val afterPan = scrollValue.value
            assertEquals(PointerEventType.PanEnd, seen.last())

            seen.clear()
            routeScroll(gestureStep(TaoScrollGesturePhase.MOMENTUM_BEGAN, dyAwt = 1f))
            routeScroll(gestureStep(TaoScrollGesturePhase.MOMENTUM_CHANGED, dyAwt = 1f))
            routeScroll(gestureStep(TaoScrollGesturePhase.MOMENTUM_ENDED, dyAwt = 0f))
            frameUntilIdle()
            // Two Scroll for the two steps with a delta; the zero-delta tail
            // end is skipped, as AWT skips zero deltas.
            assertEquals(listOf(PointerEventType.Scroll, PointerEventType.Scroll), seen.toList())
            assertTrue(
                scrollValue.value > afterPan,
                "the tail must still move content (${scrollValue.value} vs $afterPan)",
            )
        }

    private fun gestureStep(
        phase: TaoScrollGesturePhase,
        dyAwt: Float,
    ) = TaoPointerScrollEvent(dxAwt = 0f, dyAwt = dyAwt, scrollAmount = 1, gesturePhase = phase)

    /** Records Scroll / Pan event types seen on the Initial pass, without consuming. */
    private fun Modifier.recording(seen: MutableList<PointerEventType>): Modifier =
        pointerInput(seen) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    when (event.type) {
                        PointerEventType.Scroll,
                        PointerEventType.PanStart,
                        PointerEventType.PanMove,
                        PointerEventType.PanEnd,
                        -> seen += event.type
                        else -> Unit
                    }
                }
            }
        }

    private companion object {
        const val RED = 0xFFFF0000.toInt()
        const val BLUE = 0xFF0000FF.toInt()

        /** One 10-point finger move at 1x, i.e. one AWT wheel unit × 10 dp. */
        const val PAN_STEP_PX = 10f
    }
}
