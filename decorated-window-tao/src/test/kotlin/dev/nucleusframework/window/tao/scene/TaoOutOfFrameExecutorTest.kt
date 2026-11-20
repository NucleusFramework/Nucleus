// Owner.outOfFrameExecutor is a compose-ui internal: plain static access, no reflection.
@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE", "KotlinRedundantDiagnosticSuppress")
@file:OptIn(ExperimentalComposeUiApi::class)

package dev.nucleusframework.window.tao.scene

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.node.OutOfFrameExecutor
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowExceptionHandler
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TaoOutOfFrameExecutorTest {
    private fun TaoSceneTestScope.ownerExecutor(): OutOfFrameExecutor? =
        semanticsOwners()
            .single()
            .rootSemanticsNode
            .layoutNode
            .owner
            ?.outOfFrameExecutor

    @Test
    fun `work scheduled outside a frame runs without one`() =
        runTaoSceneTest {
            setContent { Box(Modifier) }
            val executor: OutOfFrameExecutor? = ownerExecutor()
            assertTrue(executor != null, "the Tao scene must hand Compose an out-of-frame executor")

            var executed = false
            executor!!.schedule { executed = true }
            pumpUntilIdle()

            assertTrue(executed)
        }

    @Test
    fun `work scheduled during a frame runs before the next one`() =
        runTaoSceneTest {
            setContent { Box(Modifier) }
            val executor: OutOfFrameExecutor = ownerExecutor()!!

            val order = mutableListOf<String>()
            // Stands for SubcomposeLayout scheduling during layout: nothing pumps
            // the scene's dispatcher between this and the next render.
            executor.schedule { order += "first" }
            executor.schedule { order += "second" }
            renderWithoutPumping()

            // Upstream order: last scheduled first.
            assertEquals(listOf("second", "first"), order)
        }

    @Test
    fun `lazy items scrolled away are deactivated after the frame`() =
        runTaoSceneTest {
            val state = LazyListState()
            val disposed = mutableSetOf<Int>()
            setContent {
                LazyColumn(state = state) {
                    items(count = 200) { index ->
                        Box(Modifier.fillMaxWidth().height(20.dp))
                        DisposableEffect(index) { onDispose { disposed += index } }
                    }
                }
            }

            state.requestScrollToItem(100)
            frame()
            val disposedInFrame = disposed.toSet()
            pumpUntilIdle()

            // Item 0 went to the reuse pool: its deactivation (and so its
            // onDispose) is deferred out of the frame that scrolled it away.
            assertTrue(0 !in disposedInFrame, "deactivated inside the frame: $disposedInFrame")
            assertTrue(0 in disposed, "never deactivated: $disposed")
        }

    @Test
    fun `work deferred by a frame runs on the loop without another frame`() =
        runTaoSceneTest {
            var executor: OutOfFrameExecutor? = null
            // Read in layout: bumping it re-runs the measure block in the next frame.
            var layoutPass by mutableIntStateOf(0)
            var executed = false
            setContent {
                Box(
                    Modifier.layout { measurable, constraints ->
                        if (layoutPass > 0) executor!!.schedule { executed = true }
                        val placeable = measurable.measure(constraints)
                        layout(placeable.width, placeable.height) { placeable.place(0, 0) }
                    },
                )
            }
            executor = ownerExecutor()!!

            layoutPass++
            // Delivers the write's own invalidation, so the frame below starts clean.
            pumpUntilIdle()
            frame()

            // The drain posted to the loop runs it: no frame is needed for that.
            assertFalse(executed, "ran inside the frame that deferred it")
            assertFalse(isSceneInvalidated, "a frame was re-armed for work a drain will run")
            pumpUntilIdle()
            assertTrue(executed)
        }

    @Test
    fun `a block throwing out of a posted drain leaves the rest to the next frame`() =
        runTaoSceneTest {
            val seen = mutableListOf<Throwable>()
            exceptionHandler = WindowExceptionHandler { seen += it }
            setContent { Box(Modifier) }
            val executor: OutOfFrameExecutor = ownerExecutor()!!

            var executed = false
            executor.schedule { executed = true }
            executor.schedule { error("deactivation failed") }
            pumpUntilIdle()

            assertEquals(1, seen.size, "the throw must reach the window's handler")
            assertFalse(executed)
            assertTrue(isSceneInvalidated, "the rest of the work must re-arm a frame")
            renderWithoutPumping()
            assertTrue(executed)
        }
}
