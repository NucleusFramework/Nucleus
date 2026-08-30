@file:OptIn(InternalComposeUiApi::class)

package dev.nucleusframework.window.tao.popup

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.nucleusframework.window.tao.GlobalLayoutDirection
import dev.nucleusframework.window.tao.scene.TaoPlatformContextBase
import dev.nucleusframework.window.tao.scene.TaoSceneBundle
import dev.nucleusframework.window.tao.scene.TaoWindowInfo
import dev.nucleusframework.window.tao.scene.canvasLayersSceneBundle
import dev.nucleusframework.window.tao.scene.recordSceneToPicture
import kotlinx.coroutines.CoroutineDispatcher
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Repro for the TrayApp scrollbar-drag crash: dragging a LazyColumn scrollbar
 * thumb scrolls synchronously inside the pointer event
 * (`LazyListState.onScroll` → `forceRemeasure`), subcomposing new items whose
 * `LaunchedEffect`s dispatch into the scene dispatcher. The standalone hosts'
 * flushing dispatcher schedules a frame on every dispatch — rendered inline,
 * that frame re-enters `measureAndLayout` while the first pass is still on
 * the stack and Compose throws
 * `performMeasureAndLayout called during measure layout`.
 *
 * The fixture mirrors the standalone hosts' wiring (`FlushingDispatcher` +
 * [StandaloneFramePump] whose render drains then measures/draws through the
 * production record path), including the [StandaloneFramePump.nonReentrant]
 * guard around scene entry points — kept in sync deliberately, like the
 * pointer dispatch shapes in `TaoSceneTestHarness`.
 */
class StandalonePopupRenderReentryTest {
    @Test
    fun `guarded scrollbar drag posts the frame instead of re-entering the render pass`() {
        Fixture().use { f ->
            f.setContent()
            f.dragThumb(guarded = true)
            assertEquals(0, f.reentrantRenders, "no frame may render while a scene entry is on the stack")
            assertTrue(
                f.listState.firstVisibleItemIndex > 0,
                "the drag must actually have scrolled the list",
            )
        }
    }

    @Test
    fun `unguarded scene dispatch re-enters the render pass - the failure mode the guard exists for`() {
        Fixture().use { f ->
            f.setContent()
            // The re-entrant measureAndLayout throws inside the effect
            // coroutine; depending on the Compose version the Recomposer
            // captures it ("Error was captured in composition") or it
            // propagates out of sendPointerEvent — either way the re-entry
            // itself is what the counter pins down.
            runCatching { f.dragThumb(guarded = false) }
            assertTrue(
                f.reentrantRenders > 0,
                "an inline render from inside the drag's measure pass should have re-entered the render pass",
            )
        }
    }

    private class Fixture : AutoCloseable {
        val listState = LazyListState()

        /** Frames rendered while a scene entry (pointer dispatch) was still on the stack. */
        var reentrantRenders = 0
            private set
        private var sceneEntryDepth = 0

        private val queue = ConcurrentLinkedQueue<Runnable>()
        private val posted = ArrayDeque<Runnable>()
        private var timeNanos = 0L
        private var bundle: TaoSceneBundle? = null

        // isOnMain is always true: the test thread stands in for the Tao main
        // thread, exactly like StandaloneFramePumpTest's probe.
        private val pump: StandaloneFramePump =
            StandaloneFramePump(
                isOnMain = { true },
                post = { posted.addLast(it) },
            ) { renderNow() }

        // Mirrors the hosts' FlushingDispatcher: queue the block, schedule a frame.
        private val dispatcher =
            object : CoroutineDispatcher() {
                override fun dispatch(
                    context: CoroutineContext,
                    block: Runnable,
                ) {
                    queue.add(block)
                    pump.schedule()
                }
            }

        init {
            bundle =
                canvasLayersSceneBundle(
                    coroutineContext = dispatcher,
                    density = Density(1f),
                    layoutDirection = GlobalLayoutDirection,
                    size = IntSize(WIDTH, HEIGHT),
                    platformContext =
                        object : TaoPlatformContextBase() {
                            override val windowInfo: WindowInfo =
                                TaoWindowInfo().apply {
                                    isWindowFocused = true
                                    containerSize = IntSize(WIDTH, HEIGHT)
                                }
                        },
                    requestFrame = { pump.schedule() },
                )
        }

        /** Mirrors `TaoStandalonePopupHost.renderNow`: drain, then measure + draw. */
        private fun renderNow() {
            if (sceneEntryDepth > 0) reentrantRenders++
            var remaining = queue.size
            while (remaining-- > 0) queue.poll()?.run()
            val b = bundle ?: return
            timeNanos += FRAME_NANOS
            recordSceneToPicture(b, WIDTH, HEIGHT, timeNanos)
        }

        fun setContent() {
            pump.nonReentrant {
                bundle!!.scene.setContent {
                    Row(Modifier.fillMaxSize()) {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                        ) {
                            items(ITEM_COUNT) { index ->
                                // The crash needs a coroutine dispatched from inside
                                // the drag's measure pass: the LaunchedEffect of a
                                // freshly subcomposed item is exactly that.
                                LaunchedEffect(index) { }
                                Box(Modifier.fillMaxWidth().height(ITEM_HEIGHT_DP.dp))
                            }
                        }
                        VerticalScrollbar(
                            adapter = rememberScrollbarAdapter(listState),
                            modifier = Modifier.fillMaxHeight(),
                        )
                    }
                }
            }
            pump.schedule()
            drainPosted()
        }

        /**
         * Grabs the scrollbar thumb and drags it down in mouse-sized steps,
         * like a real drag: each delta scrolls synchronously (`scrollBy` →
         * `forceRemeasure`), subcomposing a batch of fresh items mid-measure.
         * A single huge jump would take the adapter's snap path instead and
         * miss the re-entrancy window.
         */
        fun dragThumb(guarded: Boolean) {
            val x = WIDTH - 4f
            pointer(PointerEventType.Move, x, THUMB_Y, button = null, guarded = guarded)
            pointer(PointerEventType.Press, x, THUMB_Y, button = PointerButton.Primary, guarded = guarded)
            var y = THUMB_Y
            while (y < DRAG_TO_Y) {
                y += DRAG_STEP
                pointer(PointerEventType.Move, x, y, button = null, guarded = guarded)
            }
            pointer(PointerEventType.Release, x, y, button = PointerButton.Primary, guarded = guarded)
        }

        private fun pointer(
            eventType: PointerEventType,
            x: Float,
            y: Float,
            button: PointerButton?,
            guarded: Boolean,
        ) {
            val send = {
                sceneEntryDepth++
                try {
                    bundle!!.scene.sendPointerEvent(
                        eventType = eventType,
                        position = Offset(x, y),
                        type = PointerType.Mouse,
                        button = button,
                    )
                } finally {
                    sceneEntryDepth--
                }
            }
            if (guarded) pump.nonReentrant(send) else send()
            drainPosted()
        }

        private fun drainPosted() {
            while (posted.isNotEmpty()) posted.removeFirst().run()
        }

        override fun close() {
            // The unguarded test leaves the scene mid-crash; teardown must not
            // mask the assertion.
            runCatching { bundle?.close() }
            bundle = null
        }

        private companion object {
            const val WIDTH = 300
            const val HEIGHT = 300
            const val ITEM_COUNT = 400
            const val ITEM_HEIGHT_DP = 20
            const val THUMB_Y = 8f
            const val DRAG_TO_Y = 240f
            const val DRAG_STEP = 8f
            const val FRAME_NANOS = 16_666_667L
        }
    }
}
