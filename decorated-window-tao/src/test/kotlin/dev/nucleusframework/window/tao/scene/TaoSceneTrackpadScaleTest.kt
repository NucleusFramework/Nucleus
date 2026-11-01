@file:OptIn(InternalComposeUiApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)

package dev.nucleusframework.window.tao.scene

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.scene.ComposeScenePointer
import androidx.compose.ui.unit.dp
import dev.nucleusframework.window.tao.event.TaoTrackpadScaleSession
import dev.nucleusframework.window.tao.event.dispatchTrackpadScale
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * #660: a platform-recognized pinch must reach Compose as `ScaleStart` /
 * `ScaleChange` / `ScaleEnd` at the cursor, not as two synthetic Touch
 * contacts 120 px off it.
 *
 * The first tests replay the pre-#660 synthesis so the bug stays measurable
 * (dual-hit at a map edge, touch-slop delay on a 1 % pinch). The rest drive
 * [dispatchTrackpadScale] — the production path after the fix.
 */
class TaoSceneTrackpadScaleTest {
    // ── Reproduction of the pre-#660 two-touch synthesis ───────────────────

    @Test
    fun `legacy two-touch pinch plants contacts 120 px off the cursor`() =
        runTaoSceneTest(width = 400, height = 200) {
            val contacts = mutableListOf<Offset>()
            setContent {
                Box(Modifier.fillMaxSize().recordingPositions(contacts))
            }
            moveMouse(CURSOR_X, CURSOR_Y)
            frameUntilIdle()
            contacts.clear()
            sendLegacyPinch(PointerEventType.Press, scale = 1f, CURSOR_X, CURSOR_Y)
            frameUntilIdle()

            val unique = contacts.distinct()
            assertEquals(2, unique.size, "legacy pinch must plant two Touch contacts, got $contacts")
            val distances = unique.map { hypot(it.x - CURSOR_X, it.y - CURSOR_Y) }
            distances.forEach { distance ->
                assertEquals(
                    LEGACY_RADIUS_PX.toDouble(),
                    distance.toDouble(),
                    absoluteTolerance = 0.01,
                    message = "legacy contact $distance px from cursor; expected $LEGACY_RADIUS_PX px",
                )
            }
            println(
                "REPRO #660 geometry: cursor=($CURSOR_X, $CURSOR_Y) contacts=$unique " +
                    "distances=$distances span=${hypot(unique[0].x - unique[1].x, unique[0].y - unique[1].y)}",
            )
        }

    @Test
    fun `legacy two-touch pinch at a map edge hits the neighbouring chrome`() =
        runTaoSceneTest(width = 400, height = 200) {
            val mapHits = mutableListOf<Offset>()
            val chromeHits = mutableListOf<Offset>()
            setContent {
                Box(Modifier.fillMaxSize()) {
                    Box(
                        Modifier
                            .fillMaxHeight()
                            .width(MAP_WIDTH_DP.dp)
                            .background(Color.Blue)
                            .recordingPositions(mapHits),
                    )
                    Box(
                        Modifier
                            .offset(x = MAP_WIDTH_DP.dp)
                            .fillMaxHeight()
                            .width(CHROME_WIDTH_DP.dp)
                            .background(Color.Red)
                            .recordingPositions(chromeHits),
                    )
                }
            }
            // Cursor 10 px inside the map, next to the chrome. The 120 px
            // synthetic pair straddles the boundary: one contact in the map,
            // the other in the chrome — the edge interruption MapLibre saw.
            moveMouse(NEAR_EDGE_X, CURSOR_Y)
            frameUntilIdle()
            mapHits.clear()
            chromeHits.clear()
            sendLegacyPinch(PointerEventType.Press, scale = 1f, NEAR_EDGE_X, CURSOR_Y)
            frameUntilIdle()

            println(
                "REPRO #660 dual-hit: cursor=$NEAR_EDGE_X (map is 0..$MAP_WIDTH_PX) " +
                    "mapHits=$mapHits chromeHits=$chromeHits",
            )
            assertTrue(mapHits.isNotEmpty(), "one synthetic contact must land in the map, got mapHits=$mapHits")
            assertTrue(
                chromeHits.isNotEmpty(),
                "the other synthetic contact must land in the neighbouring chrome " +
                    "(the #660 edge interruption); chromeHits=$chromeHits",
            )
        }

    @Test
    fun `legacy two-touch pinch delays a 1 percent zoom behind touch slop`() =
        runTaoSceneTest(width = 400, height = 200) {
            val zoom = mutableStateOf(1f)
            val callbacks = mutableStateOf(0)
            setContent {
                Box(
                    Modifier.fillMaxSize().pointerInput(Unit) {
                        detectTransformGestures { _, _, zoomChange, _ ->
                            callbacks.value++
                            zoom.value *= zoomChange
                        }
                    },
                )
            }
            moveMouse(CURSOR_X, CURSOR_Y)
            sendLegacyPinch(PointerEventType.Press, scale = 1f, CURSOR_X, CURSOR_Y)
            sendLegacyPinch(PointerEventType.Move, scale = ONE_PERCENT, CURSOR_X, CURSOR_Y)
            frameUntilIdle()

            println(
                "REPRO #660 slop: 1% pinch through two-touch synthesis → " +
                    "callbacks=${callbacks.value} zoom=${zoom.value} " +
                    "(zoomMotion = |1-$ONE_PERCENT| × $LEGACY_RADIUS_PX = " +
                    "${abs(1f - ONE_PERCENT) * LEGACY_RADIUS_PX} px vs ~18 px touchSlop)",
            )
            assertEquals(0, callbacks.value, "a 1% pinch must not cross detectTransformGestures touch slop")
            assertEquals(1f, zoom.value)
        }

    @Test
    fun `legacy two-touch pinch needs about 15 percent before detectTransformGestures zooms`() =
        runTaoSceneTest(width = 400, height = 200) {
            val zoom = mutableStateOf(1f)
            val callbacks = mutableStateOf(0)
            setContent {
                Box(
                    Modifier.fillMaxSize().pointerInput(Unit) {
                        detectTransformGestures { _, _, zoomChange, _ ->
                            callbacks.value++
                            zoom.value *= zoomChange
                        }
                    },
                )
            }
            moveMouse(CURSOR_X, CURSOR_Y)
            sendLegacyPinch(PointerEventType.Press, scale = 1f, CURSOR_X, CURSOR_Y)
            var steps = 0
            var scale = 1f
            while (callbacks.value == 0 && steps < MAX_SLOP_STEPS) {
                scale *= ONE_PERCENT
                steps++
                sendLegacyPinch(PointerEventType.Move, scale = scale, CURSOR_X, CURSOR_Y)
                frameUntilIdle()
            }
            println(
                "REPRO #660 hesitation: $steps steps of +1% (cumulative scale=$scale, " +
                    "${((scale - 1f) * 100f).toInt()}%) before detectTransformGestures fired " +
                    "(callbacks=${callbacks.value} zoom=${zoom.value})",
            )
            assertTrue(callbacks.value > 0, "eventually the slop must be crossed")
            assertTrue(
                steps >= MIN_SLOP_STEPS,
                "expected a long slop delay, got a callback after $steps × 1% steps",
            )
        }

    // ── Production Scale path (#660) ───────────────────────────────────────

    @Test
    fun `magnify is dispatched as ScaleStart ScaleChange ScaleEnd at the cursor`() =
        runTaoSceneTest(width = 400, height = 200) {
            val seen = mutableListOf<ScaleRecord>()
            setContent { Box(Modifier.fillMaxSize().recordingScale(seen)) }
            moveMouse(CURSOR_X, CURSOR_Y)
            scale(PointerEventType.ScaleStart)
            scale(PointerEventType.ScaleChange, ONE_PERCENT)
            scale(PointerEventType.ScaleEnd)
            frameUntilIdle()

            assertEquals(
                listOf(
                    PointerEventType.ScaleStart,
                    PointerEventType.ScaleChange,
                    PointerEventType.ScaleEnd,
                ),
                seen.map { it.type },
                "pinch must reach Compose as Scale events, got $seen",
            )
            assertEquals(ONE_PERCENT, seen[1].scaleFactor)
            seen.forEach { record ->
                assertEquals(1, record.pointerCount, "Scale events must carry one pointer, got $record")
                assertEquals(PointerType.Mouse, record.pointerType)
                assertEquals(CURSOR_X, record.position.x)
                assertEquals(CURSOR_Y, record.position.y)
            }
            println("FIX #660 events: $seen")
        }

    @Test
    fun `scale events at a map edge hit only the map under the cursor`() =
        runTaoSceneTest(width = 400, height = 200) {
            val mapHits = mutableListOf<Offset>()
            val chromeHits = mutableListOf<Offset>()
            setContent {
                Box(Modifier.fillMaxSize()) {
                    Box(
                        Modifier
                            .fillMaxHeight()
                            .width(MAP_WIDTH_DP.dp)
                            .background(Color.Blue)
                            .recordingPositions(mapHits),
                    )
                    Box(
                        Modifier
                            .offset(x = MAP_WIDTH_DP.dp)
                            .fillMaxHeight()
                            .width(CHROME_WIDTH_DP.dp)
                            .background(Color.Red)
                            .recordingPositions(chromeHits),
                    )
                }
            }
            moveMouse(NEAR_EDGE_X, CURSOR_Y)
            frameUntilIdle()
            mapHits.clear()
            chromeHits.clear()
            scale(PointerEventType.ScaleStart)
            scale(PointerEventType.ScaleChange, ONE_PERCENT)
            scale(PointerEventType.ScaleEnd)
            frameUntilIdle()

            println("FIX #660 hit-test: mapHits=$mapHits chromeHits=$chromeHits")
            assertTrue(mapHits.isNotEmpty(), "the Scale event must hit the map under the cursor")
            assertTrue(
                chromeHits.isEmpty(),
                "Scale events must not hit neighbouring chrome, got chromeHits=$chromeHits",
            )
        }

    @Test
    fun `a 1 percent scale change zooms transformable immediately`() =
        runTaoSceneTest(width = 400, height = 200) {
            val zoom = mutableStateOf(1f)
            setContent {
                val state =
                    @Suppress("DEPRECATION")
                    rememberTransformableState { zoomChange, _, _ ->
                        zoom.value *= zoomChange
                    }
                Box(Modifier.fillMaxSize().transformable(state))
            }
            moveMouse(CURSOR_X, CURSOR_Y)
            scale(PointerEventType.ScaleStart)
            scale(PointerEventType.ScaleChange, ONE_PERCENT)
            scale(PointerEventType.ScaleEnd)
            frameUntilIdle()

            println("FIX #660 transformable: 1% ScaleChange → zoom=${zoom.value}")
            assertEquals(
                ONE_PERCENT.toDouble(),
                zoom.value.toDouble(),
                absoluteTolerance = 0.0001,
                message = "transformable must apply the ScaleChange ratio with no slop, got ${zoom.value}",
            )
        }

    @Test
    fun `detectTransformGestures is not the Scale path and stays quiet on a 1 percent pinch`() =
        runTaoSceneTest(width = 400, height = 200) {
            val callbacks = mutableStateOf(0)
            setContent {
                Box(
                    Modifier.fillMaxSize().pointerInput(Unit) {
                        detectTransformGestures { _, _, _, _ -> callbacks.value++ }
                    },
                )
            }
            moveMouse(CURSOR_X, CURSOR_Y)
            scale(PointerEventType.ScaleStart)
            scale(PointerEventType.ScaleChange, ONE_PERCENT)
            scale(PointerEventType.ScaleEnd)
            frameUntilIdle()
            assertEquals(
                0,
                callbacks.value,
                "detectTransformGestures must not re-interpret Scale events as a two-finger pinch",
            )
        }

    @Test
    fun `host-shaped magnify stream zooms transformable without slop`() =
        runTaoSceneTest(width = 400, height = 200) {
            val zoom = mutableStateOf(1f)
            setContent {
                val state =
                    @Suppress("DEPRECATION")
                    rememberTransformableState { zoomChange, _, _ ->
                        zoom.value *= zoomChange
                    }
                Box(Modifier.fillMaxSize().transformable(state))
            }
            moveMouse(CURSOR_X, CURSOR_Y)
            val session =
                TaoTrackpadScaleSession { type, factor ->
                    scene.dispatchTrackpadScale(CURSOR_X, CURSOR_Y, type, factor)
                    frame()
                }
            // macOS: Began, then a 1% Changed, then Ended — the AppKit stream.
            session.start()
            session.magnifyBy(0.01f)
            session.end()
            frameUntilIdle()
            println("FIX #660 host stream: Began + 1% Changed + Ended → zoom=${zoom.value}")
            assertEquals(
                ONE_PERCENT.toDouble(),
                zoom.value.toDouble(),
                absoluteTolerance = 0.0001,
                message = "the host magnify stream must zoom immediately, got ${zoom.value}",
            )
        }

    /**
     * Pre-#660 host synthesis: two Touch pointers [LEGACY_RADIUS_PX] either
     * side of [centerX]/[centerY], distance scaled by [scale].
     */
    private fun TaoSceneTestScope.sendLegacyPinch(
        eventType: PointerEventType,
        scale: Float,
        centerX: Float,
        centerY: Float,
    ) {
        val radius = LEGACY_RADIUS_PX * scale
        val pressed = eventType != PointerEventType.Release
        scene.sendPointerEvent(
            eventType = eventType,
            pointers =
                listOf(
                    ComposeScenePointer(
                        id = PointerId(LEGACY_POINTER_ID_A),
                        position = Offset(centerX - radius, centerY),
                        pressed = pressed,
                        type = PointerType.Touch,
                    ),
                    ComposeScenePointer(
                        id = PointerId(LEGACY_POINTER_ID_B),
                        position = Offset(centerX + radius, centerY),
                        pressed = pressed,
                        type = PointerType.Touch,
                    ),
                ),
        )
        frame()
    }

    private fun Modifier.recordingPositions(into: MutableList<Offset>): Modifier =
        pointerInput(into) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    event.changes.forEach { into += it.position }
                }
            }
        }

    private fun Modifier.recordingScale(into: MutableList<ScaleRecord>): Modifier =
        pointerInput(into) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    when (event.type) {
                        PointerEventType.ScaleStart,
                        PointerEventType.ScaleChange,
                        PointerEventType.ScaleEnd,
                        -> {
                            val change = event.changes.first()
                            into +=
                                ScaleRecord(
                                    type = event.type,
                                    scaleFactor = change.scaleFactor,
                                    position = change.position,
                                    pointerCount = event.changes.size,
                                    pointerType = change.type,
                                )
                        }
                        else -> Unit
                    }
                }
            }
        }

    private data class ScaleRecord(
        val type: PointerEventType,
        val scaleFactor: Float,
        val position: Offset,
        val pointerCount: Int,
        val pointerType: PointerType,
    )

    private companion object {
        const val CURSOR_X = 200f
        const val CURSOR_Y = 100f
        const val LEGACY_RADIUS_PX = 120f
        const val LEGACY_POINTER_ID_A = 0xA001L
        const val LEGACY_POINTER_ID_B = 0xA002L
        const val ONE_PERCENT = 1.01f
        const val MAP_WIDTH_DP = 150
        const val CHROME_WIDTH_DP = 250
        const val MAP_WIDTH_PX = 150f
        const val NEAR_EDGE_X = 140f
        const val MAX_SLOP_STEPS = 40
        const val MIN_SLOP_STEPS = 10
    }
}
