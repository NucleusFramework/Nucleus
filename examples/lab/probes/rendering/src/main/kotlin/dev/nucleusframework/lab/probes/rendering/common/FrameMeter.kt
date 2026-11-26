package dev.nucleusframework.lab.probes.rendering.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import dev.nucleusframework.lab.core.format.fmt
import dev.nucleusframework.lab.core.time.NANOS_PER_MILLI
import dev.nucleusframework.lab.designsystem.Readout
import dev.nucleusframework.lab.designsystem.Tone
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** One second of frame telemetry. */
@Immutable
data class FrameSample(
    val framesPerSecond: Int = 0,
    val worstFrameMillis: Double = 0.0,
    val averageFrameMillis: Double = 0.0,
)

/**
 * Allocation-free frame counter for hot paths (draw passes, producer threads), sampled
 * once a second. Producers call [tick]; [sample] turns the last second into a [FrameSample].
 *
 * Tick it from a draw pass (`drawBehind { meter.tick() }`), never from a `withFrameNanos`
 * loop: awaiting frames requests them, so an idle scene would look busy.
 */
class FrameMeter {
    private val frames = AtomicInteger()
    private val worstNanos = AtomicLong()
    private val totalNanos = AtomicLong()
    private val lastTick = AtomicLong()

    fun tick(nowNanos: Long = System.nanoTime()) {
        frames.incrementAndGet()
        val previous = lastTick.getAndSet(nowNanos)
        // A gap longer than a sampling window is idleness, not a slow frame.
        if (previous != 0L && nowNanos - previous < IDLE_GAP_NANOS) {
            val delta = nowNanos - previous
            totalNanos.addAndGet(delta)
            worstNanos.accumulateAndGet(delta, ::maxOf)
        }
    }

    fun sample(): FrameSample {
        val count = frames.getAndSet(0)
        val worst = worstNanos.getAndSet(0)
        val total = totalNanos.getAndSet(0)
        return FrameSample(
            framesPerSecond = count,
            worstFrameMillis = worst.toMillis(),
            averageFrameMillis = if (count > 0) (total / count).toMillis() else 0.0,
        )
    }

    private fun Long.toMillis(): Double = toDouble() / NANOS_PER_MILLI

    private companion object {
        const val IDLE_GAP_NANOS = 1_000_000_000L
    }
}

/**
 * The meter's rate, refreshed once a second in a composable of its own, so the state read
 * (and the recomposition it causes) stays out of the content being measured.
 */
@Composable
fun FrameRateReadout(
    label: String,
    meter: FrameMeter,
    modifier: Modifier = Modifier,
    /** Above this the frame is flagged (one dropped frame at 60 Hz by default). */
    budgetMillis: Double = 33.4,
) {
    var sample by remember { mutableStateOf(FrameSample()) }
    LaunchedEffect(meter) {
        while (isActive) {
            delay(1_000)
            sample = meter.sample()
        }
    }
    Readout(
        label,
        "${sample.framesPerSecond} fps · avg ${sample.averageFrameMillis.fmt(1)} ms · " +
            "worst ${sample.worstFrameMillis.fmt(1)} ms",
        modifier,
        tone = if (sample.worstFrameMillis > budgetMillis) Tone.Warning else Tone.Neutral,
    )
}

/**
 * Counts recompositions of the call site: `val count = rememberRecompositionCounter()`. A plain
 * array, so reading it never schedules anything.
 */
@Composable
fun rememberRecompositionCounter(): IntArray {
    val counter = remember { intArrayOf(0) }
    counter[0]++
    return counter
}
