package dev.nucleusframework.window.tao.headful

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import dev.nucleusframework.core.runtime.Platform
import dev.nucleusframework.window.tao.TaoApplication
import dev.nucleusframework.window.tao.TaoEventLoopWatchdog
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.AtomicInteger
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger
import kotlin.random.Random

/**
 * The watchdog monkey against a **running app** (#643).
 *
 * `EventLoopWatchdogMonkeyHeadfulCases` freezes a real but idle window. An idle
 * loop is the easy case: nothing is in flight when the pump stops. This one
 * freezes an app that is *working* — an infinite Compose transition driving a
 * frame every vsync, a second real window (`DecoratedDialog`) opening and
 * closing under the storm, and the freezes landing inside that traffic, which
 * is the shape #640 actually had.
 *
 * Two things only this can check:
 *
 * - **the animation survives**: after every freeze, `withFrameNanos` must tick
 *   again. A watchdog that perturbed the loop — a probe that posted, a callback
 *   that ran on the wrong thread — would show up as frames that never resume,
 *   and nothing in a unit test can see that.
 * - **windows coming and going are tracked**: the dialog is a real second
 *   window, so the storm exercises `WINDOW_READY` / `DESTROYED` registration
 *   against a live watchdog rather than a hand-called `registerWindow`.
 */
internal object EventLoopWatchdogAnimationHeadfulCases {
    fun all(): List<TaoWindowTestCase> {
        val frames = AtomicInteger()
        val dialogVisible = mutableStateOf(true)
        return listOf(
            TaoWindowTestCase(
                "watchdog monkey: animating app, real dialog, real freezes (#643)",
                timeoutMillis = CASE_TIMEOUT_MS,
                skip = {
                    if (Platform.Current != Platform.Windows) {
                        "IsHungAppWindow is Windows-only — no non-perturbing probe elsewhere yet"
                    } else {
                        null
                    }
                },
                dialogSize = DpSize(DIALOG_DP.dp, DIALOG_DP.dp),
                dialogVisible = dialogVisible,
                dialogContent = { Spinner(Color(DIALOG_ARGB)) },
                content = {
                    Spinner(Color(WINDOW_ARGB))
                    LaunchedEffect(Unit) {
                        // The app's own frame pulse. It is what a wedged loop
                        // stops producing, and what must come back afterwards.
                        while (true) {
                            withFrameNanos { frames.incrementAndGet() }
                        }
                    }
                },
                driver = { storm(frames, dialogVisible) },
            ),
        )
    }

    @Suppress("LongMethod") // one flat case: setup, storm, invariants
    private suspend fun TaoWindowTestScope.storm(
        frames: AtomicInteger,
        dialogVisible: androidx.compose.runtime.MutableState<Boolean>,
    ) {
        awaitUntil("window mapped") { window.hasRealFramePx() }
        awaitUntil("the app is animating") { frames.get() > FRAMES_BEFORE_START }
        settle()

        val records = ConcurrentLinkedDeque<LogRecord>()
        val logger = Logger.getLogger(TaoEventLoopWatchdog::class.java.name)
        val collector =
            object : Handler() {
                override fun publish(record: LogRecord) {
                    records += record
                }

                override fun flush() = Unit

                override fun close() = Unit
            }
        logger.addHandler(collector)

        val unresponsive = AtomicInteger()
        val responsive = AtomicInteger()
        TaoApplication.onUnresponsive { unresponsive.incrementAndGet() }
        TaoApplication.onResponsive { responsive.incrementAndGet() }

        val previousGrace = System.getProperty(GRACE_PROPERTY)
        System.setProperty(GRACE_PROPERTY, "0")
        TaoEventLoopWatchdog.stop()
        TaoEventLoopWatchdog.start()
        TaoEventLoopWatchdog.registerWindow(window.handle)

        val random = Random(monkeySeed())
        val journal = mutableListOf<String>()
        var expectedReports = 0
        try {
            repeat(MOVES) { move ->
                val action = AnimatedMove.entries[random.nextInt(AnimatedMove.entries.size)]
                journal += "#$move $action"
                val framesBefore = frames.get()
                val reportsBefore = records.count { it.level == Level.SEVERE }
                when (action) {
                    AnimatedMove.ShortFreeze -> Thread.sleep(random.nextLong(300, SHORT_FREEZE_MAX_MS))
                    AnimatedMove.LongFreeze -> {
                        Thread.sleep(random.nextLong(LONG_FREEZE_MIN_MS, LONG_FREEZE_MAX_MS))
                        expectedReports++
                        // Waited for here, not counted at the end: a report that
                        // lands during the *next* move would otherwise read as
                        // "a short freeze was reported".
                        awaitUntil("the long freeze was reported", timeoutMillis = REPORT_TIMEOUT_MS) {
                            records.count { it.level == Level.SEVERE } > reportsBefore
                        }
                    }
                    AnimatedMove.ExpectedLongFreeze ->
                        TaoApplication.expectUnresponsive {
                            Thread.sleep(random.nextLong(LONG_FREEZE_MIN_MS, LONG_FREEZE_MAX_MS))
                        }
                    AnimatedMove.ToggleDialog -> dialogVisible.value = !dialogVisible.value
                    AnimatedMove.RestartWatchdog -> {
                        TaoEventLoopWatchdog.stop()
                        TaoEventLoopWatchdog.start()
                        TaoEventLoopWatchdog.registerWindow(window.handle)
                    }
                }
                settle(SETTLE_MS)

                // The app must be animating again, whatever just happened to it.
                val target = framesBefore + FRAMES_AFTER_MOVE
                if (!awaitUntilOrTimeout(FRAME_RESUME_TIMEOUT_MS) { frames.get() > target }) {
                    // Stuck. Ask the window for one frame: if that unsticks it,
                    // the animation's own invalidation was lost rather than the
                    // clock being dead — a host bug, not a watchdog one, and the
                    // distinction is the whole value of this failure.
                    window.requestRedraw()
                    val nudged = awaitUntilOrTimeout(FRAME_RESUME_TIMEOUT_MS) { frames.get() > target }
                    val verdict =
                        if (nudged) {
                            "frames only resumed after an explicit requestRedraw"
                        } else {
                            "frames never resumed"
                        }
                    error("$verdict after $action; stuck at ${frames.get()} (was $framesBefore), journal=$journal")
                }

                val reports = records.count { it.level == Level.SEVERE }
                if (action == AnimatedMove.ShortFreeze && reports != reportsBefore) {
                    error("a freeze under the OS threshold was reported; journal=$journal")
                }
                if (action == AnimatedMove.ExpectedLongFreeze && reports != reportsBefore) {
                    error("a freeze inside expectUnresponsive was reported; journal=$journal")
                }
            }

            val reports = records.count { it.level == Level.SEVERE }
            check(reports >= expectedReports) {
                "only $reports report(s) for $expectedReports long freeze(s); journal=$journal"
            }
            awaitUntil(
                "every unresponsive paired with a responsive",
                timeoutMillis = PAIRING_TIMEOUT_MS,
                detail = { "unresponsive=${unresponsive.get()} responsive=${responsive.get()}" },
            ) {
                unresponsive.get() == responsive.get() && unresponsive.get() >= expectedReports
            }
        } finally {
            logger.removeHandler(collector)
            if (previousGrace == null) {
                System.clearProperty(GRACE_PROPERTY)
            } else {
                System.setProperty(GRACE_PROPERTY, previousGrace)
            }
            dialogVisible.value = true
            TaoEventLoopWatchdog.stop()
            TaoEventLoopWatchdog.start()
        }

        // The app is still an app: it animates, and it still holds a real frame.
        val settled = frames.get()
        awaitUntil("the app is still animating after the storm") { frames.get() > settled + FRAMES_AFTER_MOVE }
        awaitUntil("the window still reports a real frame") { window.hasRealFramePx() }
    }

    /** A cheap always-moving thing: real recomposition, real frames, real GPU work. */
    @Composable
    private fun Spinner(color: Color) {
        val transition = rememberInfiniteTransition(label = "watchdog-monkey")
        val angle by transition.animateFloat(
            initialValue = 0f,
            targetValue = FULL_TURN,
            animationSpec =
                infiniteRepeatable(
                    animation = tween(durationMillis = SPIN_MS, easing = LinearEasing),
                    repeatMode = RepeatMode.Restart,
                ),
            label = "angle",
        )
        Box(Modifier.fillMaxSize().background(Color(BACKDROP_ARGB))) {
            Box(
                Modifier
                    .size(SPINNER_DP.dp)
                    .rotate(angle)
                    .graphicsLayer { alpha = lerp(HALF_ALPHA, 1f, angle / FULL_TURN) }
                    .background(color),
            )
        }
    }

    private enum class AnimatedMove {
        ShortFreeze,
        LongFreeze,
        ExpectedLongFreeze,
        ToggleDialog,
        RestartWatchdog,
    }

    private const val GRACE_PROPERTY = "nucleus.tao.watchdogGraceMs"
    private const val MOVES = 10
    private const val SHORT_FREEZE_MAX_MS = 2_500L
    private const val LONG_FREEZE_MIN_MS = 8_000L
    private const val LONG_FREEZE_MAX_MS = 11_000L
    private const val SETTLE_MS = 3_000L
    private const val PAIRING_TIMEOUT_MS = 20_000L
    private const val REPORT_TIMEOUT_MS = 20_000L
    private const val FRAME_RESUME_TIMEOUT_MS = 15_000L
    private const val CASE_TIMEOUT_MS = 300_000L
    private const val FRAMES_BEFORE_START = 5
    private const val FRAMES_AFTER_MOVE = 3
    private const val DIALOG_DP = 240
    private const val SPINNER_DP = 120
    private const val SPIN_MS = 1_200
    private const val FULL_TURN = 360f
    private const val HALF_ALPHA = 0.4f
    private const val WINDOW_ARGB = 0xFF3D7EFF
    private const val DIALOG_ARGB = 0xFFFF9F0A
    private const val BACKDROP_ARGB = 0xFF1E1F22
}
