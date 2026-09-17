package dev.nucleusframework.window.tao.scene

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Regression tests for #615 — "PointerInput consumes touches (Tao backend)".
 *
 * Tao delivers sub-pixel cursor positions (1/1024 px wire format) and macOS
 * emits a CursorMoved before every mouseDown/mouseUp, so a click whose cursor
 * drifts a fraction of a pixel between press and release produces a real Move
 * delta. Compose's mouse slop is only `touchSlop × (0.125.dp / 18.dp)`
 * (foundation `DragGestureDetector.kt`), so ~0.2 px of drift starts a parent
 * drag gesture which consumes the move; a child `clickable` then sees the
 * consumed change in the Final pass and cancels the tap — "buttons need two
 * clicks". The AWT backend never sees this because it quantizes positions to
 * integer points, a de-facto 1 dp deadband.
 *
 * Contract under test (host + harness share the same dispatch shapes):
 *  - Moves under 1 dp from the last *dispatched* position are suppressed;
 *  - press/release dispatch at the last dispatched position (otherwise
 *    Compose's SyntheticEventSender re-injects the suppressed delta);
 *  - motion past the deadband keeps its exact sub-pixel position;
 *  - `viewConfiguration.touchSlop` is 18.dp in physical px (AWT parity),
 *    not 18 raw px.
 */
@OptIn(ExperimentalComposeUiApi::class)
class TaoScenePointerSlopTest {
    @Test
    fun `sub-pixel jitter between press and release must not eat the click`() =
        runTaoSceneTest(width = 200, height = 200) {
            var clicks = 0
            var dragStarts = 0
            setContent {
                DragParentWithClickableChild(
                    onDragStart = { dragStarts++ },
                    onClick = { clicks++ },
                )
            }
            moveMouse(30f, 30f)
            pointerButton(PointerButton.Primary, pressed = true)
            // macOS view.rs emits a CursorMoved before every mouseUp — a
            // trackpad click that slides 0.2 px reports this exact stream.
            moveMouse(30.2f, 30f)
            pointerButton(PointerButton.Primary, pressed = false)
            assertEquals(0, dragStarts, "sub-pixel jitter must not start a drag")
            assertEquals(1, clicks, "the click must reach the child clickable")
        }

    @Test
    fun `sub-pixel jitter must not eat the click on a HiDPI display`() =
        runTaoSceneTest(width = 200, height = 200, density = 2f) {
            var clicks = 0
            var dragStarts = 0
            setContent {
                DragParentWithClickableChild(
                    onDragStart = { dragStarts++ },
                    onClick = { clicks++ },
                )
            }
            moveMouse(30f, 30f)
            pointerButton(PointerButton.Primary, pressed = true)
            moveMouse(30.2f, 30f)
            pointerButton(PointerButton.Primary, pressed = false)
            assertEquals(0, dragStarts, "sub-pixel jitter must not start a drag")
            assertEquals(1, clicks, "the click must reach the child clickable")
        }

    @Test
    fun `real motion past the deadband still drags and cancels the click`() =
        runTaoSceneTest(width = 200, height = 200) {
            var clicks = 0
            var dragStarts = 0
            setContent {
                DragParentWithClickableChild(
                    onDragStart = { dragStarts++ },
                    onClick = { clicks++ },
                )
            }
            moveMouse(30f, 30f)
            pointerButton(PointerButton.Primary, pressed = true)
            moveMouse(33f, 30f)
            moveMouse(36f, 30f)
            pointerButton(PointerButton.Primary, pressed = false)
            assertEquals(1, dragStarts, "real motion must start the drag")
            assertEquals(0, clicks, "a drag must still cancel the click")
        }

    @Test
    fun `moves below one dp are suppressed and real motion keeps sub-pixel precision`() =
        runTaoSceneTest(width = 200, height = 200) {
            val moves = mutableListOf<Offset>()
            setContent {
                Box(
                    Modifier.fillMaxSize().onPointerEvent(PointerEventType.Move) {
                        moves += it.changes.first().position
                    },
                )
            }
            moveMouse(100f, 100f)
            moveMouse(100.4f, 100f) // 0.4 px from last dispatched — jitter
            moveMouse(100.8f, 100f) // still < 1 dp from last dispatched
            moveMouse(101.5f, 100f) // 1.5 px from last dispatched — real motion
            assertTrue(
                moves.none { it.x > 100f && it.x < 101.5f },
                "sub-dp jitter must be suppressed (got $moves)",
            )
            assertEquals(
                101.5f,
                moves.last().x,
                "motion past the deadband must keep its exact sub-pixel position",
            )
        }

    @Test
    fun `press after suppressed jitter dispatches at the last dispatched position`() =
        runTaoSceneTest(width = 200, height = 200) {
            var pressPosition: Offset? = null
            setContent {
                Box(
                    Modifier.fillMaxSize().onPointerEvent(PointerEventType.Press) {
                        pressPosition = it.changes.first().position
                    },
                )
            }
            moveMouse(100f, 100f)
            moveMouse(100.3f, 100f) // suppressed
            pointerButton(PointerButton.Primary, pressed = true)
            pointerButton(PointerButton.Primary, pressed = false)
            // Anywhere else and SyntheticEventSender re-injects the suppressed
            // delta as a synthetic Move right before the Press.
            assertEquals(Offset(100f, 100f), pressPosition)
        }

    @Test
    fun `touch slop is density-scaled like the AWT backend`() =
        runTaoSceneTest(width = 200, height = 200, density = 2f) {
            var touchSlop = 0f
            setContent {
                touchSlop = LocalViewConfiguration.current.touchSlop
            }
            assertEquals(36f, touchSlop, "touchSlop must be 18.dp in physical px (18 × density)")
        }

    /** The #615 repro shape: parent drag detector, child clickable. */
    @Composable
    private fun DragParentWithClickableChild(
        onDragStart: () -> Unit,
        onClick: () -> Unit,
    ) {
        Box(Modifier.fillMaxSize()) {
            Box(
                Modifier
                    .size(100.dp)
                    .pointerInput(Unit) {
                        detectDragGestures(onDragStart = { onDragStart() }) { change, _ ->
                            change.consume()
                        }
                    },
            ) {
                Box(Modifier.size(60.dp).clickable { onClick() })
            }
        }
    }
}
