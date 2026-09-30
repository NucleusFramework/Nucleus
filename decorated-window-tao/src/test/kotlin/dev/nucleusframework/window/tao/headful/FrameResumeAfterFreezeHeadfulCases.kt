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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import dev.nucleusframework.core.runtime.Platform
import java.util.concurrent.atomic.AtomicInteger

/**
 * A window that stops painting after a long freeze, even though its loop is
 * pumping again (host bug, found by the #643 watchdog monkey).
 *
 * `TaoWindow.requestRedraw` latches [TaoWindow] `redrawPending` to coalesce, and
 * only the matching `REDRAW_REQUESTED` clears it. The code already knows two
 * ways the OS can swallow that event and leave the latch armed forever — a
 * nested modal pump (`resetRedrawLatch`) and an occluding modal child (the
 * `FOCUSED` branch) — and both carry the same symptom in their comments:
 * "frozen until I click on it again".
 *
 * This is the third way. A freeze past ~5 s makes Windows ghost the window; the
 * redraw that was in flight when the thread blocked never comes back, so an app
 * that recovers from a long synchronous operation keeps a live event loop and a
 * dead picture — which is strictly worse than the freeze, because nothing
 * suggests the app is still there.
 *
 * The case is deliberately watchdog-free: it freezes, waits, and if frames have
 * not resumed it tries the two repairs in order — a plain `requestRedraw`
 * (a no-op while the latch is armed, so it proves nothing on its own) and then
 * `resetRedrawLatch`. Which one revives the animation names the culprit.
 */
internal object FrameResumeAfterFreezeHeadfulCases {
    fun all(): List<TaoWindowTestCase> {
        val frames = AtomicInteger()
        val dialogVisible = mutableStateOf(true)
        return listOf(
            TaoWindowTestCase(
                "frames resume after a long freeze (#643 host lead)",
                timeoutMillis = CASE_TIMEOUT_MS,
                skip = {
                    // The ghosting that swallows the redraw is Windows'.
                    if (Platform.Current != Platform.Windows) "window ghosting is a Windows behaviour" else null
                },
                dialogSize = DpSize(DIALOG_DP.dp, DIALOG_DP.dp),
                dialogVisible = dialogVisible,
                dialogContent = { Box(Modifier.fillMaxSize().background(Color(DIALOG_ARGB))) },
                content = {
                    val transition = rememberInfiniteTransition(label = "frame-resume")
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
                        Box(Modifier.size(SPINNER_DP.dp).rotate(angle).background(Color(SPINNER_ARGB)))
                    }
                    LaunchedEffect(Unit) {
                        while (true) {
                            withFrameNanos { frames.incrementAndGet() }
                        }
                    }
                },
            ) {
                awaitUntil("window mapped") { window.hasRealFramePx() }
                awaitUntil("the app is animating") { frames.get() > FRAMES_BEFORE }
                settle()

                repeat(FREEZES) { round ->
                    // The dialog is what makes the main window an occluded one,
                    // which is the state whose dropped redraws the host already
                    // repairs on FOCUSED. Freezing across that transition is the
                    // variant nothing covers.
                    when (round % DIALOG_PHASES) {
                        1 -> dialogVisible.value = false
                        2 -> dialogVisible.value = true
                        else -> Unit
                    }
                    settle(DIALOG_SETTLE_MS)
                    val before = frames.get()
                    // Long enough for Windows to ghost the window, which is what
                    // swallows the redraw that was in flight.
                    Thread.sleep(FREEZE_MS)

                    if (awaitUntilOrTimeout(RESUME_TIMEOUT_MS) { frames.get() > before + FRAMES_AFTER }) {
                        return@repeat
                    }
                    // Stalled. A plain request first: it is a no-op while the
                    // latch is armed, so if this revives the window the latch was
                    // not the problem.
                    window.requestRedraw()
                    val plainWorked = awaitUntilOrTimeout(RESUME_TIMEOUT_MS) { frames.get() > before + FRAMES_AFTER }
                    if (plainWorked) {
                        error("round $round: frames only resumed after a plain requestRedraw (invalidation dropped)")
                    }
                    window.resetRedrawLatch()
                    val latchWorked = awaitUntilOrTimeout(RESUME_TIMEOUT_MS) { frames.get() > before + FRAMES_AFTER }
                    error(
                        if (latchWorked) {
                            "round $round: the redraw latch was stuck — resetRedrawLatch revived the window, " +
                                "so a ${FREEZE_MS}ms freeze leaves an app with a live loop and a dead picture"
                        } else {
                            "round $round: frames never resumed, and clearing the redraw latch did not help"
                        },
                    )
                }
            },
        )
    }

    private const val FREEZES = 6
    private const val FREEZE_MS = 7_000L
    private const val RESUME_TIMEOUT_MS = 4_000L
    private const val FRAMES_BEFORE = 5
    private const val FRAMES_AFTER = 3
    private const val CASE_TIMEOUT_MS = 180_000L
    private const val DIALOG_DP = 220
    private const val DIALOG_PHASES = 3
    private const val DIALOG_SETTLE_MS = 700L
    private const val DIALOG_ARGB = 0xFFFF9F0A
    private const val SPINNER_DP = 120
    private const val SPIN_MS = 1_200
    private const val FULL_TURN = 360f
    private const val SPINNER_ARGB = 0xFF3D7EFF
    private const val BACKDROP_ARGB = 0xFF1E1F22
}
