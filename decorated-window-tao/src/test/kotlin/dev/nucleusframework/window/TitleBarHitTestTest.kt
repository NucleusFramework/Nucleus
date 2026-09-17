package dev.nucleusframework.window

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import dev.nucleusframework.window.tao.TaoWindow
import dev.nucleusframework.window.tao.scene.TaoSceneTestScope
import dev.nucleusframework.window.tao.scene.runTaoSceneTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Hit-test contract of the title-bar drag handler and of the Overlay
 * pass-through marker, driven through the stage-1 scene harness.
 *
 * The layouts here mirror `WindowScaffold`'s `TitleBarPlacement.Overlay`
 * branch (content first, bar wrapper on top, optionally carrying
 * [shareHitTestWithSiblings]) rather than invoking the scaffold itself: the
 * scaffold needs a `DecoratedWindowScope` and platform chrome probing, neither
 * of which belongs in a stage-1 test.
 */
class TitleBarHitTestTest {
    @Test
    fun `opaque overlay bar does not leak clicks to the content below`() =
        runTaoSceneTest(width = 200, height = 200) {
            var contentClicks = 0
            setContent {
                overlayLayout(
                    passThroughToContent = false,
                    window = TaoWindow(handle = 0L),
                    content = {
                        Box(Modifier.fillMaxWidth().height(BAR_HEIGHT).clickable { contentClicks++ })
                    },
                )
            }
            click(100f, 20f)
            assertEquals(0, contentClicks, "a press on the chrome must stay in the chrome")
        }

    @Test
    fun `pass-through overlay bar keeps content in the bar band interactive`() =
        runTaoSceneTest(width = 200, height = 200) {
            var contentClicks = 0
            setContent {
                overlayLayout(
                    passThroughToContent = true,
                    window = TaoWindow(handle = 0L),
                    content = {
                        Box(Modifier.fillMaxWidth().height(BAR_HEIGHT).clickable { contentClicks++ })
                    },
                )
            }
            click(100f, 20f)
            assertEquals(1, contentClicks, "opted-in pass-through must reach the content sibling")
        }

    @Test
    fun `content consuming the press vetoes the window drag`() =
        runTaoSceneTest(width = 200, height = 200) {
            val window = TaoWindow(handle = 0L)
            var drags = 0
            window.onDragWindow { drags++ }
            setContent {
                overlayLayout(passThroughToContent = true, window = window) {
                    Box(Modifier.fillMaxWidth().height(BAR_HEIGHT).clickable { })
                }
            }
            dragFromBar()
            assertEquals(0, drags, "a consumed press must not arm the window move")
        }

    @Test
    fun `an unclaimed press on the bar still drags the window`() =
        runTaoSceneTest(width = 200, height = 200) {
            val window = TaoWindow(handle = 0L)
            var drags = 0
            window.onDragWindow { drags++ }
            setContent {
                overlayLayout(passThroughToContent = true, window = window) {
                    Box(Modifier.fillMaxSize())
                }
            }
            dragFromBar()
            assertEquals(1, drags)
        }

    @Test
    fun `a consumer that stops consuming mid-gesture never hands over the drag`() =
        runTaoSceneTest(width = 200, height = 300) {
            val window = TaoWindow(handle = 0L)
            var drags = 0
            window.onDragWindow { drags++ }
            var consumedMoves = 0
            setContent {
                overlayLayout(passThroughToContent = true, window = window) {
                    // A scrollable that reaches its limit behaves exactly like
                    // this: it claims the first samples of the gesture, then
                    // stops consuming. The drag must stay disarmed.
                    Box(
                        Modifier.fillMaxSize().pointerInput(Unit) {
                            awaitPointerEventScope {
                                while (true) {
                                    val event = awaitPointerEvent(PointerEventPass.Main)
                                    if (event.type == PointerEventType.Move && consumedMoves < 3) {
                                        consumedMoves++
                                        event.changes.forEach { it.consume() }
                                    }
                                }
                            }
                        },
                    )
                }
            }
            dragFromBar(steps = 6)
            assertEquals(3, consumedMoves, "the probe must actually have claimed the first samples")
            assertEquals(0, drags, "the window must not start moving mid-gesture")
        }

    @Test
    fun `a drag gesture under a pass-through bar is not stolen by the window move`() =
        runTaoSceneTest(width = 200, height = 300) {
            val window = TaoWindow(handle = 0L)
            var drags = 0
            window.onDragWindow { drags++ }
            var dragged = 0
            setContent {
                overlayLayout(passThroughToContent = true, window = window) {
                    Box(
                        Modifier.fillMaxSize().pointerInput(Unit) {
                            detectDragGestures { change, _ ->
                                change.consume()
                                dragged++
                            }
                        },
                    )
                }
            }
            dragFromBar(steps = 6)
            assertEquals(6, dragged)
            assertEquals(0, drags)
        }

    @Test
    fun `a drag area does not leak clicks to an overlapping sibling`() =
        runTaoSceneTest(width = 200, height = 200) {
            var contentClicks = 0
            setContent {
                // The macOS fullscreen bar shape: the bar is offset over the
                // content and is its DIRECT sibling, so a sharing flag on the
                // drag handler's own node would apply here.
                Box(Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxSize()) {
                        Box(Modifier.fillMaxWidth().height(BAR_HEIGHT).clickable { contentClicks++ })
                    }
                    Box(
                        Modifier
                            .align(Alignment.TopStart)
                            .fillMaxWidth()
                            .height(BAR_HEIGHT)
                            .titleBarHitTestHandler(TaoWindow(handle = 0L)),
                    )
                }
            }
            click(100f, 20f)
            assertEquals(0, contentClicks)
        }

    private companion object {
        val BAR_HEIGHT = 40.dp

        @Composable
        fun overlayLayout(
            passThroughToContent: Boolean,
            window: TaoWindow,
            content: @Composable () -> Unit,
        ) {
            Box(Modifier.fillMaxSize()) {
                content()
                Box(
                    modifier =
                        Modifier
                            .align(Alignment.TopStart)
                            .let { if (passThroughToContent) it.shareHitTestWithSiblings() else it },
                ) {
                    Box(Modifier.fillMaxWidth().height(BAR_HEIGHT).titleBarHitTestHandler(window))
                }
            }
        }

        /** Press inside the bar band, then drag straight down out of it. */
        fun TaoSceneTestScope.dragFromBar(steps: Int = 3) {
            moveMouse(100f, 20f)
            pointerButton(PointerButton.Primary, pressed = true)
            var y = 20f
            repeat(steps) {
                y += 40f
                moveMouse(100f, y)
            }
            pointerButton(PointerButton.Primary, pressed = false)
        }
    }
}
