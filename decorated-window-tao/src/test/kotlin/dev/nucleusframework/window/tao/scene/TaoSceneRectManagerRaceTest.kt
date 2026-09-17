package dev.nucleusframework.window.tao.scene

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onLayoutRectChanged
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Issue #551: Compose 1.12's RectManager schedules its debounce dispatch via
 * desktop `postDelayed`, which runs on skiko's `MainUIDispatcher` — the AWT
 * EDT. On AWT hosts the EDT *is* the Compose thread, so the dispatch is
 * serialized with layout. On Tao the Compose thread is the native event-loop
 * thread, so the ~16ms-delayed `RectManager.dispatchCallbacks()` (including
 * `RectList.defragment()`) executes on the EDT concurrently with layout
 * running on the scene thread — corrupting the RectList and later throwing
 * `IllegalArgumentException: LayoutNode not found in RectList` from
 * `LayoutNode.detach`.
 *
 * This test forces the overlap deterministically: every tab switch detaches a
 * tree (making the RectList fragmented) and mounts a new one whose placement
 * takes well over 16ms (per-node busy spins), so the timer armed by the first
 * placement fires mid-layout on the EDT while inserts are still running.
 */
class TaoSceneRectManagerRaceTest {
    @Test
    fun `EDT-delayed RectManager dispatch must not race scene-thread layout`() =
        runTaoSceneTest(width = 600, height = 400) {
            var tab by mutableStateOf(0)

            setContent {
                Column(Modifier.fillMaxSize()) {
                    when (tab) {
                        0 -> SlowBody(Color.Red)
                        else -> SlowBody(Color.Blue)
                    }
                }
            }

            repeat(60) {
                tab = (tab + 1) % 2
                // The remount frame: applyChanges detaches the old tree
                // (fragmenting the RectList) and the new tree's slow placement
                // keeps the scene thread inserting rects for >16ms, straddling
                // the debounce timer deadline.
                frame()
                // Give the EDT a moment so a timer that survived the frame can
                // fire between frames too (visibility-race variant).
                Thread.sleep(2)
            }
            frameUntilIdle()
        }

    /**
     * The guard pins the debounce deadline to keep the EDT out — this checks
     * the debounce *feature* still works: `onLayoutRectChanged` trailing edges
     * must keep firing, now from the scene-thread post-frame hook instead of
     * the upstream EDT timer.
     */
    @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
    @Test
    fun `debounced onLayoutRectChanged trailing edge still fires with the guard pinned`() =
        runTaoSceneTest(width = 200, height = 200) {
            var offset by mutableStateOf(0)
            var callbacks = 0

            setContent {
                Column(Modifier.fillMaxSize()) {
                    Box(Modifier.height(offset.dp))
                    Box(
                        Modifier
                            .size(40.dp)
                            .background(Color.Red)
                            .onLayoutRectChanged(
                                throttleMillis = 0,
                                debounceMillis = 64,
                            ) { callbacks++ },
                    )
                }
            }
            // The debounce compares real wall-clock millis; let it elapse for
            // the initial fire, then frame so the post-frame hook dispatches.
            frameUntilIdle()
            Thread.sleep(DEBOUNCE_ELAPSE_MS)
            frame()
            val baseline = callbacks

            // Move the observed box, then let the 64ms debounce elapse again.
            offset = 60
            frame()
            Thread.sleep(DEBOUNCE_ELAPSE_MS)
            frame()
            assertTrue(
                callbacks > baseline,
                "debounced onLayoutRectChanged must still fire (got $callbacks, baseline $baseline)",
            )
        }

    private companion object {
        /** Comfortably past the 64ms `onLayoutRectChanged` debounce. */
        const val DEBOUNCE_ELAPSE_MS = 150L
    }

    @Suppress("FunctionNaming")
    @androidx.compose.runtime.Composable
    private fun SlowBody(color: Color) {
        Column(Modifier.fillMaxSize()) {
            repeat(15) { row ->
                Row {
                    repeat(10) { col ->
                        Box(
                            Modifier
                                .layout { measurable, constraints ->
                                    val placeable = measurable.measure(constraints)
                                    layout(placeable.width, placeable.height) {
                                        // ~0.2ms per node: ~30ms of placement per
                                        // remount, guaranteeing the frame overlaps
                                        // the 16ms debounce deadline.
                                        val end = System.nanoTime() + 200_000L
                                        while (System.nanoTime() < end) {
                                            Thread.onSpinWait()
                                        }
                                        placeable.place(0, 0)
                                    }
                                }.size(8.dp)
                                .background(if ((row + col) % 2 == 0) color else Color.Gray),
                        )
                    }
                }
            }
        }
    }
}
