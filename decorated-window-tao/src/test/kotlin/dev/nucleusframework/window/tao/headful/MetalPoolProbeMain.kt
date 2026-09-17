package dev.nucleusframework.window.tao.headful

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.rememberWindowState
import dev.nucleusframework.window.tao.DecoratedWindow
import dev.nucleusframework.window.tao.taoApplication
import kotlinx.coroutines.delay
import kotlin.concurrent.thread
import kotlin.system.exitProcess

/**
 * Child process of `TaoMetalMissingPoolE2ETest` (#494): opens a real Tao window
 * with a continuously animating box (`rememberInfiniteTransition`, the exact
 * repro from the issue) so the Metal render thread acquires a drawable and
 * submits a command buffer on every frame, then exits after a few seconds.
 *
 * The parent test launches this main with `OBJC_DEBUG_MISSING_POOLS=YES` and
 * counts the ObjC runtime's "autoreleased with no pool in place" diagnostics
 * on stderr — the env var must be set at process launch, which is why this
 * runs as a separate process rather than inside the test JVM.
 *
 * Prints `[pool-probe] frames=N` (frame-clock ticks during the animation
 * window) so the parent can verify frames were actually rendered — without it
 * a broken launch would pass vacuously with zero warnings.
 */
object MetalPoolProbeMain {
    private const val SETTLE_MS = 2_000L
    private const val ANIMATE_MS = 4_000L
    private const val SPIN_PERIOD_MS = 500
    private const val WATCHDOG_MS = 60_000L
    private const val WATCHDOG_EXIT_CODE = 42

    @JvmStatic
    fun main(args: Array<String>) {
        // The tao event loop takes over this thread; if exitApplication is
        // never reached the probe would hang the parent test's waitFor.
        thread(isDaemon = true, name = "pool-probe-watchdog") {
            Thread.sleep(WATCHDOG_MS)
            Runtime.getRuntime().halt(WATCHDOG_EXIT_CODE)
        }

        taoApplication {
            DecoratedWindow(
                onCloseRequest = ::exitApplication,
                state = rememberWindowState(size = DpSize(420.dp, 320.dp)),
                title = "pool-probe #494",
            ) {
                val spin = rememberInfiniteTransition(label = "spin")
                val angle by spin.animateFloat(
                    initialValue = 0f,
                    targetValue = 360f,
                    animationSpec = infiniteRepeatable(tween(SPIN_PERIOD_MS, easing = LinearEasing)),
                    label = "angle",
                )
                Box(
                    Modifier.fillMaxSize().background(Color.DarkGray),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        Modifier
                            .size(160.dp)
                            .graphicsLayer { rotationZ = angle }
                            .background(Color(0xFF3366CC)),
                    )
                }
                LaunchedEffect(Unit) {
                    delay(SETTLE_MS)
                    var frames = 0
                    val end = System.currentTimeMillis() + ANIMATE_MS
                    while (System.currentTimeMillis() < end) {
                        withFrameNanos { }
                        frames++
                    }
                    println("[pool-probe] frames=$frames")
                    exitApplication()
                }
            }
        }
        exitProcess(0)
    }
}
