package dev.nucleusframework.window.tao.scene

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.nucleusframework.core.runtime.Platform
import dev.nucleusframework.window.tao.TaoPointerScrollEvent
import dev.nucleusframework.window.tao.event.AWT_PIXEL_TO_ROTATION
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Stage-1 scroll tests: wire-shaped [TaoPointerScrollEvent]s (with the
 * production AWT-compatible synthetic wheel event attached, exactly like
 * `TaoComposeSceneHost.onPointerScroll`) move real scrollable content.
 */
class TaoSceneScrollTest {
    private fun scrollEvent(
        dy: Float,
        scrollAmount: Int = 3,
    ) = TaoPointerScrollEvent(dxAwt = 0f, dyAwt = dy, scrollAmount = scrollAmount)

    @Test
    fun `wheel down scrolls a vertical column`() =
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
            scroll(scrollEvent(dy = 1f))
            frameUntilIdle()
            assertTrue(scrollValue.value > 0, "scroll state must advance (got ${scrollValue.value})")
        }

    @Test
    fun `wheel up at top is a no-op`() =
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
            scroll(scrollEvent(dy = -1f))
            frameUntilIdle()
            assertEquals(0, scrollValue.value)
        }

    @Test
    fun `scroll direction is symmetric`() =
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
            scroll(scrollEvent(dy = 1f))
            frameUntilIdle()
            val afterDown = scrollValue.value
            assertTrue(afterDown > 0, "scroll state must advance after down (got $afterDown)")
            scroll(scrollEvent(dy = -1f))
            // MouseWheelScrollingLogic tweens each notch (~100 ms). LinuxGnomeConfig
            // also scales by sqrt(viewport)*scrollAmount (~42 px here), so two
            // leftover animation frames already exceed the old 8 px floor under
            // CI load. Drain until leftover is stable, then allow a fraction of
            // the downward notch — reverse must still move toward origin.
            frameUntilIdle()
            var leftover = scrollValue.value
            var previous = leftover + 1
            var passes = 0
            var stable = 0
            while (stable < 2 && passes < SYMMETRY_SETTLE_PASSES) {
                previous = leftover
                frameUntilIdle()
                leftover = scrollValue.value
                passes++
                stable = if (leftover == previous) stable + 1 else 0
            }
            val tolerance = maxOf(SYMMETRY_RESIDUE_PX, afterDown / 2)
            assertTrue(
                leftover < afterDown && kotlin.math.abs(leftover) <= tolerance,
                "one notch down then one notch up must return near origin " +
                    "(afterDown=$afterDown, leftover=$leftover after $passes extra settle passes, " +
                    "tolerance=${tolerance}px)",
            )
        }

    @Test
    fun `larger scrollAmount scrolls further per notch`() {
        var smallAmount = 0
        var largeAmount = 0
        runTaoSceneTest(width = 100, height = 200) {
            val scrollValue = mutableStateOf(0)
            setContent {
                val state = rememberScrollState()
                scrollValue.value = state.value
                Column(Modifier.fillMaxSize().verticalScroll(state)) {
                    repeat(100) { Box(Modifier.fillMaxWidth().height(20.dp)) }
                }
            }
            moveMouse(50f, 100f)
            scroll(scrollEvent(dy = 1f, scrollAmount = 1))
            frameUntilIdle()
            smallAmount = scrollValue.value
        }
        runTaoSceneTest(width = 100, height = 200) {
            val scrollValue = mutableStateOf(0)
            setContent {
                val state = rememberScrollState()
                scrollValue.value = state.value
                Column(Modifier.fillMaxSize().verticalScroll(state)) {
                    repeat(100) { Box(Modifier.fillMaxWidth().height(20.dp)) }
                }
            }
            moveMouse(50f, 100f)
            scroll(scrollEvent(dy = 1f, scrollAmount = 6))
            frameUntilIdle()
            largeAmount = scrollValue.value
        }
        assertTrue(
            largeAmount > smallAmount,
            "scrollAmount=6 ($largeAmount px) must out-scroll scrollAmount=1 ($smallAmount px)",
        )
    }

    @Test
    fun `one wheel unit scrolls ten dp on macOS`() {
        // The factor TaoSceneScrollRouter sizes trackpad pans with
        // (AWT_PIXEL_TO_ROTATION) is Compose Desktop's MacOSCocoaConfig
        // `10.dp` per preciseWheelRotation; pin it so a Compose change shows
        // up here rather than as pans and notches drifting apart.
        if (Platform.Current != Platform.MacOS) return // LinuxGnomeConfig / WindowsWinUIConfig scale differently
        runTaoSceneTest(width = 100, height = 200, density = 2f) {
            val scrollValue = mutableStateOf(0)
            setContent {
                val state = rememberScrollState()
                scrollValue.value = state.value
                Column(Modifier.fillMaxSize().verticalScroll(state)) {
                    repeat(50) { Box(Modifier.fillMaxWidth().height(20.dp)) }
                }
            }
            moveMouse(50f, 100f)
            scroll(scrollEvent(dy = 1f, scrollAmount = 1))
            frameUntilIdle()
            assertEquals((AWT_PIXEL_TO_ROTATION * 2f).roundToInt(), scrollValue.value)
        }
    }

    @Test
    fun `scrolled content repaints at the new offset`() =
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
            repeat(10) { scroll(scrollEvent(dy = 3f)) }
            frameUntilIdle()
            assertEquals(BLUE, pixelAt(50, 99), "after scrolling, the blue block must be visible")
        }

    private companion object {
        const val RED = 0xFFFF0000.toInt()
        const val BLUE = 0xFF0000FF.toInt()

        /** Extra frameUntilIdle rounds after the reverse notch (CI flake). */
        const val SYMMETRY_SETTLE_PASSES = 16

        /**
         * Floor on residual pixels after the reverse notch. The actual bound is
         * `max(this, afterDown / 2)` so a LinuxGnomeConfig tween leftover
         * (sqrt(height)*scrollAmount ≈ 42 px) does not flake CI.
         */
        const val SYMMETRY_RESIDUE_PX = 24
    }
}
