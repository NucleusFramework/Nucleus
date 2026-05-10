package io.github.kdroidfilter.nucleus.window.tao

import androidx.compose.runtime.snapshots.Snapshot
import kotlinx.coroutines.CoroutineDispatcher
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.CoroutineContext

/**
 * Coroutine dispatcher that posts blocks onto the Tao main thread.
 *
 * Single-threaded model: blocks are queued in [pending] and drained by [pump]
 * on every `Event::MainEventsCleared` tick of the Tao event loop. Both
 * dispatch sites and pump call sites *must* run on the Tao main thread.
 *
 * Threading guarantee: [dispatch] is safe to call from any thread (the queue
 * is thread-safe), but [pump] must be invoked from the main thread only —
 * [TaoApplication] arranges that via the JNI event callback.
 */
internal object TaoMainDispatcher : CoroutineDispatcher() {
    private val pending = ConcurrentLinkedQueue<Runnable>()

    /**
     * Coalesces native wake calls within a single pump cycle. Set on the
     * first dispatch after pump opened the gate, cleared in [pump] right
     * after the drain. While the gate is closed, subsequent dispatches just
     * enqueue — the in-flight wake will deliver them on the next pump.
     *
     * Without this gate, Compose's coroutine machinery (especially under
     * `infiniteRepeatable` animations and snapshot-apply observers) crosses
     * JNI on every resume and pegs the Tao loop at 100% CPU even when the
     * visible UI is idle.
     */
    private val wakePending = AtomicBoolean(false)

    /**
     * Re-arm throttle. When a pumped block re-dispatches itself synchronously
     * (very common with Compose's state-observer / snapshot-apply machinery —
     * `LazyColumn` + scrollbar can cycle through ~150 k pumps/sec at idle),
     * the pump→drain→re-arm→nativeWake→pump loop pegs the Tao main thread
     * at 100 % CPU. AWT-based backends don't see this because the EDT only
     * processes pending work at ~60 Hz; we mimic that here by deferring the
     * re-arm `nativeWake` through a small delay rather than calling it
     * synchronously inside [pump]. New external dispatches still wake the
     * loop immediately — the delay only applies when the queue is non-empty
     * *after* a drain (i.e. blocks added by the just-run blocks).
     */
    private val pumpReArmIntervalNs = 1_000_000_000L / 60
    private var lastPumpNs = 0L
    private val pumpScheduler by lazy {
        Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "TaoPumpScheduler").apply { isDaemon = true }
        }
    }

    override fun dispatch(
        context: CoroutineContext,
        block: Runnable,
    ) {
        pending.offer(block)
        // Wake the Tao event loop. Tao runs with `ControlFlow::Wait` and
        // would otherwise sleep until an OS event arrives, leaving this
        // block undrained whenever no window is currently driving the loop
        // (e.g. before the first frame, or after the last window closes).
        if (NativeTaoBridge.isLoaded && wakePending.compareAndSet(false, true)) {
            NativeTaoBridge.nativeWake()
        }
    }

    /** Drains everything currently pending. New blocks dispatched while
     *  draining run on the next pump (no recursion). */
    fun pump() {
        // Snapshot the count to avoid an infinite loop when a block
        // re-dispatches itself synchronously.
        var remaining = pending.size
        var ranAnything = false
        while (remaining-- > 0) {
            val block = pending.poll() ?: break
            ranAnything = true
            try {
                block.run()
            } catch (t: Throwable) {
                // Coroutine dispatchers swallow exceptions thrown synchronously
                // from `run()`; the runtime reports them via the Recomposer's
                // exception handler. Re-throwing here would crash the Tao loop.
                t.printStackTrace()
            }
        }
        // Propagate snapshot writes performed by the blocks above. Compose's
        // `GlobalSnapshotManager` posts `Snapshot.sendApplyNotifications()`
        // to Skiko's `MainUIDispatcher` (= AWT EDT on JVM), but our event
        // loop runs on the Tao main thread independently from the EDT —
        // without this call, animation state writes performed in
        // `withFrameNanos` continuations land in the global snapshot but
        // their apply observers (notably `BaseComposeScene.
        // snapshotInvalidationTracker`) only fire whenever the EDT happens
        // to pump, freezing animations between input events.
        if (ranAnything) {
            Snapshot.sendApplyNotifications()
        }
        val pumpEndNs = System.nanoTime()
        // Open the gate so the next dispatch can re-arm. Order matters:
        // we open AFTER the drain so any re-dispatches done by the running
        // blocks above do not double-up the wake — they're already in
        // pending and will be picked up by either this pump cycle's
        // remaining iterations or the next one.
        wakePending.set(false)
        // Blocks added during drain need a fresh wake to schedule the next
        // pump. Throttle the re-arm: if we just pumped synchronously and
        // pending is non-empty due to a re-dispatch, defer the next wake
        // by `pumpReArmIntervalNs` instead of waking immediately. External
        // dispatches arriving in the meantime go through the regular
        // [dispatch] path and wake the loop straight away.
        val sinceLast = pumpEndNs - lastPumpNs
        lastPumpNs = pumpEndNs
        if (!pending.isEmpty() &&
            NativeTaoBridge.isLoaded &&
            wakePending.compareAndSet(false, true)
        ) {
            if (sinceLast < pumpReArmIntervalNs) {
                val delayNs = pumpReArmIntervalNs - sinceLast
                pumpScheduler.schedule(
                    { if (NativeTaoBridge.isLoaded) NativeTaoBridge.nativeWake() },
                    delayNs,
                    TimeUnit.NANOSECONDS,
                )
            } else {
                NativeTaoBridge.nativeWake()
            }
        }
    }
}
