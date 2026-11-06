package dev.nucleusframework.window.tao.scene

import androidx.compose.runtime.Composer
import androidx.compose.runtime.CompositionTracer
import androidx.compose.runtime.InternalComposeTracingApi
import dev.nucleusframework.window.tao.dispatch.DelayScheduler
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.LongAdder
import java.util.logging.Logger

/**
 * `-Dnucleus.compose.recomposition=true` (`debug { recomposition { } }` in the
 * Nucleus plugin): counts how often each composable runs and logs the
 * `nucleus.compose.recomposition.top` busiest ones every few seconds — what
 * Android Studio's Layout Inspector shows as recomposition counts.
 *
 * Compose's compiler brackets every composable body it does not skip with
 * `traceEventStart` / `traceEventEnd` while a [CompositionTracer] is installed;
 * the first composition counts too. One tracer for the process, installed with
 * the first scene.
 */
@OptIn(InternalComposeTracingApi::class)
internal object RecompositionCounter : CompositionTracer {
    private val enabled = System.getProperty("nucleus.compose.recomposition") == "true"
    private val top = Integer.getInteger("nucleus.compose.recomposition.top", DEFAULT_TOP)
    private val installed = AtomicBoolean(false)

    /** Runs per composable (the compiler's "fqName (File.kt:line)"), since the last report. */
    private val counts = ConcurrentHashMap<String, LongAdder>()
    private val logger = Logger.getLogger(RecompositionCounter::class.java.name)

    fun installIfEnabled() {
        if (!enabled || !installed.compareAndSet(false, true)) return
        Composer.setTracer(this)
        scheduleReport()
    }

    override fun isTraceInProgress(): Boolean = true

    override fun traceEventStart(
        key: Int,
        dirty1: Int,
        dirty2: Int,
        info: String,
    ) {
        counts.computeIfAbsent(info) { LongAdder() }.increment()
    }

    override fun traceEventEnd() = Unit

    private fun scheduleReport() {
        DelayScheduler.schedule({
            report()
            scheduleReport()
        }, REPORT_SECONDS, TimeUnit.SECONDS)
    }

    private fun report() {
        val snapshot = counts.entries.map { (info, adder) -> info to adder.sumThenReset() }.filter { it.second > 0 }
        if (snapshot.isEmpty()) return
        val busiest =
            snapshot
                .sortedByDescending { it.second }
                .take(top)
                .joinToString("\n") { (info, runs) -> "    $runs x $info" }
        logger.info("Recompositions, last ${REPORT_SECONDS}s: ${snapshot.sumOf { it.second }} runs\n$busiest")
    }

    private const val DEFAULT_TOP = 10
    private const val REPORT_SECONDS = 3L
}
