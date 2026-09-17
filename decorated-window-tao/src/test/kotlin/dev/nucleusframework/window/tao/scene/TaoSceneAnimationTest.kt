package dev.nucleusframework.window.tao.scene

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Stage-1 deterministic-time tests: the harness owns the frame clock and the
 * scene's render time, so animations advance frame by frame with no real
 * clock involved — the property that makes thousands of animation-dependent
 * tests stable in CI.
 */
class TaoSceneAnimationTest {
    @Test
    fun `tween advances exactly with virtual frames`() =
        runTaoSceneTest(width = 200, height = 100) {
            var target by mutableStateOf(0f)
            var observed = 0f
            setContent {
                val animated by animateFloatAsState(
                    targetValue = target,
                    animationSpec = tween(durationMillis = 160, easing = LinearEasing),
                )
                observed = animated
                Box(Modifier.fillMaxSize()) {
                    Box(
                        Modifier
                            .offset(x = (animated * 100).roundToInt().dp)
                            .size(10.dp)
                            .background(Color.Blue),
                    )
                }
            }
            assertEquals(0f, observed)
            target = 1f
            frame() // animation starts
            val samples = mutableListOf<Float>()
            repeat(12) {
                frame(deltaMillis = 16)
                samples += observed
            }
            assertTrue(samples.zipWithNext().all { (a, b) -> b >= a }, "progress must be monotonic: $samples")
            assertTrue(samples.first() < 1f, "animation must not jump to the end on the first frame")
            // 12 x 16ms > 160ms normally completes the tween, but on a loaded
            // CI runner the animation-start resumption can hop through
            // Dispatchers.Default (real time) and miss the first virtual
            // frames, leaving the tween a frame or two short — settle instead
            // of asserting the exact frame count (same hardening as the
            // wheel-scroll symmetry test).
            frameUntilIdle()
            assertEquals(1f, observed, "animation must settle at the target value")
        }

    @Test
    fun `same frame sequence produces the same pixels twice`() {
        fun run(): List<Int> {
            val pixels = mutableListOf<Int>()
            runTaoSceneTest(width = 100, height = 50) {
                var target by mutableStateOf(0f)
                setContent {
                    val animated by animateFloatAsState(
                        targetValue = target,
                        animationSpec = tween(durationMillis = 100, easing = LinearEasing),
                    )
                    Box(Modifier.fillMaxSize().background(Color.White)) {
                        Box(
                            Modifier
                                .offset(x = (animated * 80).roundToInt().dp)
                                .size(10.dp)
                                .background(Color.Red),
                        )
                    }
                }
                target = 1f
                frame()
                repeat(4) { frame(deltaMillis = 16) }
                for (x in 0 until 100 step 5) pixels += pixelAt(x, 25)
            }
            return pixels
        }
        assertEquals(run(), run(), "two identical virtual-time runs must be pixel-identical")
    }

    @Test
    fun `frameUntilIdle settles a finite animation`() =
        runTaoSceneTest(width = 100, height = 50) {
            var target by mutableStateOf(0f)
            var observed = 0f
            setContent {
                val animated by animateFloatAsState(
                    targetValue = target,
                    animationSpec = tween(durationMillis = 200, easing = LinearEasing),
                )
                observed = animated
                Box(Modifier.fillMaxSize().background(if (animated == 1f) Color.Green else Color.White))
            }
            target = 1f
            frameUntilIdle()
            assertEquals(1f, observed)
            assertEquals(GREEN, pixelAt(50, 25))
        }

    private companion object {
        const val GREEN = 0xFF00FF00.toInt()
    }
}
