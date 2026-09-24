package dev.nucleusframework.window.tao.scene

import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import dev.nucleusframework.window.tao.event.TaoDirectManipulationEvent
import dev.nucleusframework.window.tao.event.TaoDirectManipulationStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * #706 stage 1: a DirectManipulation stream through the Windows host's
 * touchpad input ([TaoWindowsTouchpadInput]) into a real `ComposeScene` —
 * the zoom `Modifier.transformable` applies, the distance a column scrolls,
 * and the gestures Compose sees, for the shapes a precision touchpad produces.
 */
class TaoSceneWindowsTouchpadTest {
    @Test
    fun `a pinch zooms transformable by the viewport's ratio at its focal point`() =
        runTaoSceneTest(width = 400, height = 300) {
            var zoom = 1f
            var centroid = Offset.Unspecified
            setContent {
                @Suppress("DEPRECATION")
                val state = rememberTransformableState { change, _, _ -> zoom *= change }
                Box(
                    Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            awaitPointerEventScope {
                                while (true) {
                                    val event = awaitPointerEvent(PointerEventPass.Initial)
                                    if (event.type ==
                                        PointerEventType.ScaleChange
                                    ) {
                                        centroid = event.changes.first().position
                                    }
                                }
                            }
                        }.transformable(state),
                )
            }
            val dm = Stream(this, focal = Offset(90f, 210f))
            dm.status(TaoDirectManipulationStatus.RUNNING)
            var scale = 1f
            repeat(10) {
                scale *= 1.08f
                dm.content(scale = scale)
            }
            dm.status(TaoDirectManipulationStatus.READY)
            frameUntilIdle()
            assertEquals(scale, zoom, 1e-3f * scale)
            assertEquals(Offset(90f, 210f), centroid)
            assertFalse(touchpadPinchOpen, "READY closes the pinch — no timer involved")
        }

    @Test
    fun `a pan scrolls a column by the content motion and its tail is part of the pan`() =
        runTaoSceneTest(width = 200, height = 300) {
            val scroll = mutableStateOf(0)
            val seen = mutableListOf<PointerEventType>()
            setContent {
                val state = rememberScrollState()
                scroll.value = state.value
                Column(
                    Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            awaitPointerEventScope {
                                while (true) {
                                    val type = awaitPointerEvent(PointerEventPass.Initial).type
                                    if (type == PointerEventType.PanStart ||
                                        type == PointerEventType.PanEnd
                                    ) {
                                        seen += type
                                    }
                                }
                            }
                        }.verticalScroll(state),
                ) {
                    repeat(100) { Box(Modifier.fillMaxWidth().height(20.dp)) }
                }
            }
            val dm = Stream(this, focal = Offset(100f, 150f))
            dm.status(TaoDirectManipulationStatus.RUNNING)
            var y = 0f
            // Real frame cadence: Compose stamps pointer events with the wall
            // clock, and the fling it would add at PanEnd comes from those stamps.
            repeat(10) {
                y -= 10f
                dm.content(y = y)
                Thread.sleep(FRAME_MILLIS)
            }
            dm.status(TaoDirectManipulationStatus.INERTIA)
            var glide = 8f
            while (glide > 0.1f) {
                glide *= 0.7f
                y -= glide
                dm.content(y = y)
                Thread.sleep(FRAME_MILLIS)
            }
            assertTrue(touchpadPanOpen, "the tail keeps the pan open")
            dm.status(TaoDirectManipulationStatus.READY)
            frameUntilIdle()
            assertEquals(listOf(PointerEventType.PanStart, PointerEventType.PanEnd), seen)
            assertEquals(-y, scroll.value.toFloat(), 2f, "the column moves by the viewport's distance")
        }

    @Test
    fun `a pan without inertia closes after the grace`() =
        runTaoSceneTest(width = 200, height = 300) {
            val dm = Stream(this, focal = Offset(100f, 150f))
            setContent { Box(Modifier.fillMaxSize()) }
            dm.status(TaoDirectManipulationStatus.RUNNING)
            dm.content(x = 12f)
            dm.status(TaoDirectManipulationStatus.READY)
            assertTrue(touchpadPanOpen, "ENDED waits for a possible tail")
            elapseTouchpadPanGrace()
            assertFalse(touchpadPanOpen)
        }

    @Test
    fun `a Ctrl+wheel tick folds into an open touchpad pinch`() =
        runTaoSceneTest(width = 400, height = 300) {
            var zoom = 1f
            setContent {
                @Suppress("DEPRECATION")
                val state = rememberTransformableState { change, _, _ -> zoom *= change }
                Box(Modifier.fillMaxSize().transformable(state))
            }
            moveMouse(200f, 150f)
            val dm = Stream(this, focal = Offset(200f, 150f))
            dm.status(TaoDirectManipulationStatus.RUNNING)
            dm.content(scale = 1.2f)
            ctrlWheelTick(1f)
            // The wheel path's debounce (120 ms) elapses; the pinch stays open.
            repeat(20) { frame() }
            assertTrue(touchpadPinchOpen, "only the touchpad's own phase closes its pinch")
            dm.content(scale = 1.44f)
            dm.status(TaoDirectManipulationStatus.READY)
            frameUntilIdle()
            assertFalse(touchpadPinchOpen)
            assertTrue(zoom > 1.44f, "the wheel tick zoomed inside the pinch: $zoom")
        }

    @Test
    fun `a Ctrl+wheel burst closes on its debounce and a touchpad pinch replaces it`() =
        runTaoSceneTest(width = 400, height = 300) {
            val starts = mutableListOf<PointerEventType>()
            setContent {
                Box(
                    Modifier.fillMaxSize().pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                val type = awaitPointerEvent(PointerEventPass.Initial).type
                                if (type == PointerEventType.ScaleStart ||
                                    type == PointerEventType.ScaleEnd
                                ) {
                                    starts += type
                                }
                            }
                        }
                    },
                )
            }
            moveMouse(200f, 150f)
            ctrlWheelTick(1f)
            assertTrue(touchpadPinchOpen)
            val dm = Stream(this, focal = Offset(200f, 150f))
            dm.status(TaoDirectManipulationStatus.RUNNING)
            dm.content(scale = 1.1f)
            dm.status(TaoDirectManipulationStatus.READY)
            frameUntilIdle()
            assertEquals(
                listOf(
                    PointerEventType.ScaleStart,
                    PointerEventType.ScaleEnd,
                    PointerEventType.ScaleStart,
                    PointerEventType.ScaleEnd,
                ),
                starts,
            )
        }

    @Test
    fun `a disabled window drops the steps but still closes the gesture`() =
        runTaoSceneTest(width = 400, height = 300) {
            var zoom = 1f
            setContent {
                @Suppress("DEPRECATION")
                val state = rememberTransformableState { change, _, _ -> zoom *= change }
                Box(Modifier.fillMaxSize().transformable(state))
            }
            val dm = Stream(this, focal = Offset(200f, 150f))
            dm.status(TaoDirectManipulationStatus.RUNNING)
            dm.content(scale = 1.5f)
            dm.content(scale = 2f, inputEnabled = false)
            dm.status(TaoDirectManipulationStatus.READY, inputEnabled = false)
            frameUntilIdle()
            assertFalse(touchpadPinchOpen, "the end still arrives")
            assertEquals(1.5f, zoom, 1e-3f)
        }

    private companion object {
        const val FRAME_MILLIS = 16L
    }

    /** A viewport's transform, stepped and delivered through the scope. */
    private class Stream(
        private val scope: TaoSceneTestScope,
        private val focal: Offset,
    ) {
        private var status = TaoDirectManipulationStatus.READY
        private var scale = 1f
        private var x = 0f
        private var y = 0f

        fun status(
            next: Int,
            inputEnabled: Boolean = true,
        ) {
            scope.directManipulation(
                TaoDirectManipulationEvent(
                    TaoDirectManipulationEvent.STATUS,
                    next,
                    status,
                    scale,
                    x,
                    y,
                    focal.x,
                    focal.y,
                ),
                inputEnabled,
            )
            status = next
        }

        fun content(
            scale: Float = this.scale,
            x: Float = this.x,
            y: Float = this.y,
            inputEnabled: Boolean = true,
        ) {
            this.scale = scale
            this.x = x
            this.y = y
            scope.directManipulation(
                TaoDirectManipulationEvent(
                    TaoDirectManipulationEvent.CONTENT,
                    status,
                    status,
                    scale,
                    x,
                    y,
                    focal.x,
                    focal.y,
                ),
                inputEnabled,
            )
        }
    }
}
