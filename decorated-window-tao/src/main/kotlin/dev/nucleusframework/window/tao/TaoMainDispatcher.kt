package dev.nucleusframework.window.tao

import androidx.compose.runtime.snapshots.Snapshot
import kotlinx.coroutines.CoroutineDispatcher
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.CoroutineContext

private const val FALLBACK_QUIESCE_TIMEOUT_SECONDS = 2L
private const val FALLBACK_THREAD_NAME = "Nucleus-Tao-Main-Fallback"

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
     * Thread reference for the Tao main thread. Captured eagerly from
     * [TaoApplication.run] before [NativeTaoBridge.nativeRunBlocking] takes the
     * thread over, so it is non-null as soon as user composition runs. When
     * `Dispatchers.Main` is used before any loop starts, the pre-loop fallback
     * thread captures itself here instead; [TaoApplication.run] later overwrites
     * it with the real main thread.
     *
     * Consumed by [TaoMainCoroutineDispatcher.isDispatchNeeded] and by
     * downstream `Dispatchers.Main` resolvers (most notably AndroidX
     * Lifecycle's `MainDispatcherChecker`) to recognise the Tao main thread as
     * the canonical UI thread.
     */
    @Volatile
    @JvmField
    internal var taoMainThread: Thread? = null

    /**
     * `true` once [TaoApplication.run] is about to hand the main thread to the
     * native Tao event loop (which drains [pending] via [pump] on every
     * `MAIN_EVENTS_CLEARED` tick). Until then the loop is not running, so a
     * fallback thread drains the queue instead — see [dispatch].
     *
     * Without this, adding `nucleus.decorated-window-tao` to the classpath makes
     * [TaoMainDispatcherFactory] win the `Dispatchers.Main` ServiceLoader pick,
     * but any `Dispatchers.Main` use *before* (or entirely without) a running
     * loop would enqueue forever with nothing to drain it — e.g. a bare
     * `runBlocking(Dispatchers.Main) { … }` hangs indefinitely (issue #337).
     */
    @Volatile
    private var loopRunning = false

    /**
     * Coalesces fallback-drain submissions. Reset at the very start of the
     * drain task (before draining) so any dispatch racing the drain re-arms a
     * fresh task instead of being lost.
     */
    private val fallbackDrainPending = AtomicBoolean(false)

    /**
     * Single daemon thread that drains [pending] while the native Tao loop is
     * not running. It becomes the [taoMainThread] for the loop-less case so
     * that `Dispatchers.Main.immediate` re-entrancy (e.g. AndroidX Lifecycle's
     * `runBlocking(Dispatchers.Main.immediate)` probe) runs inline instead of
     * deadlocking on the single executor thread.
     *
     * Lazily created on first pre-loop dispatch and torn down again by
     * [onNativeLoopStarting] once the native loop takes over, so it is not left
     * parked for the whole app lifetime. Lifecycle transitions are guarded by
     * [fallbackLock].
     */
    private val fallbackLock = Any()
    private var fallbackExecutor: ExecutorService? = null

    /**
     * Marks the native Tao loop as the active drainer. Called by
     * [TaoApplication.run] right before `nativeRunBlocking`, after the real main
     * thread has been captured into [taoMainThread].
     *
     * From this point [dispatch] wakes the native loop and the fallback drain is
     * retired. The fallback executor is shut down and **awaited** so no block
     * runs on the fallback thread once the native loop owns the main thread —
     * this both preserves main-thread affinity and, since it runs before
     * `LifecycleMainDispatcherPriming`, prevents a late fallback block from
     * re-poisoning AndroidX Lifecycle's `MainDispatcherChecker`. Any blocks the
     * fallback left behind are handed to [pump] via [wakeNativeForLeftovers].
     */
    internal fun onNativeLoopStarting() {
        loopRunning = true
        val executor = synchronized(fallbackLock) { fallbackExecutor.also { fallbackExecutor = null } }
        if (executor != null) {
            executor.shutdown()
            try {
                if (!executor.awaitTermination(FALLBACK_QUIESCE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    executor.shutdownNow()
                }
            } catch (_: InterruptedException) {
                executor.shutdownNow()
                Thread.currentThread().interrupt()
            }
        }
        wakeNativeForLeftovers()
    }

    /**
     * Wakes the native loop when it is the active drainer and [pending] still
     * holds blocks the fallback did not drain (or that arrived during the
     * hand-off). Without this the leftover blocks would sit undrained until an
     * unrelated OS event happened to fire `MAIN_EVENTS_CLEARED`.
     */
    private fun wakeNativeForLeftovers() {
        if (loopRunning &&
            !pending.isEmpty() &&
            NativeTaoBridge.isLoaded &&
            wakePending.compareAndSet(false, true)
        ) {
            NativeTaoBridge.nativeWake()
        }
    }

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
    private const val PUMP_RE_ARM_INTERVAL_NS = 1_000_000_000L / 60

    /**
     * Multi-pass drain bounds. [pump] drains re-dispatched blocks in additional
     * passes within the *same* tick so a finite chain of coroutine continuations
     * (e.g. `StateFlow → flatMapLatest → cachedIn → Paging collectFrom`) resolves
     * in one pump instead of one hop per throttled (~16 ms) re-arm. Without this,
     * a ~9-hop Paging pager rebuild took ~150 ms on macOS during a scrollbar drag,
     * because each hop's continuation waited a full re-arm interval. The bounds cap
     * runaway self-redispatch (infiniteRepeatable / snapshot-apply churn): once a
     * pump exceeds [MAX_PUMP_PASSES] passes or the [PUMP_TIME_BUDGET_NS] wall-clock
     * budget, it stops and yields to rendering, falling back to the throttled re-arm.
     */
    private const val MAX_PUMP_PASSES = 64
    private const val PUMP_TIME_BUDGET_NS = 8_000_000L // 8 ms
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
        if (loopRunning) {
            // Wake the Tao event loop. Tao runs with `ControlFlow::Wait` and
            // would otherwise sleep until an OS event arrives, leaving this
            // block undrained whenever no window is currently driving the loop
            // (e.g. before the first frame, or after the last window closes).
            if (NativeTaoBridge.isLoaded && wakePending.compareAndSet(false, true)) {
                NativeTaoBridge.nativeWake()
            }
        } else {
            // The native loop is not running yet (or never will — e.g. a bare
            // `runBlocking(Dispatchers.Main) { … }` with no `nucleusApplication`).
            // Drive the queue on a dedicated fallback thread so Dispatchers.Main
            // works regardless. Handed off to `pump()` once the loop starts.
            scheduleFallbackDrain()
        }
    }

    private fun scheduleFallbackDrain() {
        if (!fallbackDrainPending.compareAndSet(false, true)) return
        // Resolve (creating on demand) the executor under the lock so we never
        // race onNativeLoopStarting's shutdown. If the loop has since taken
        // over, there is nothing to schedule — hand the block to pump() instead.
        val executor =
            synchronized(fallbackLock) {
                if (loopRunning) null else fallbackExecutor ?: newFallbackExecutor().also { fallbackExecutor = it }
            }
        if (executor == null) {
            fallbackDrainPending.set(false)
            wakeNativeForLeftovers()
            return
        }
        try {
            executor.execute {
                // Reset before draining so a dispatch racing this task re-arms
                // a fresh drain rather than being dropped.
                fallbackDrainPending.set(false)
                if (taoMainThread == null) {
                    taoMainThread = Thread.currentThread()
                }
                drainFallback()
            }
        } catch (_: RejectedExecutionException) {
            // onNativeLoopStarting shut the executor down between the lock and
            // here; the loop now owns draining.
            fallbackDrainPending.set(false)
            wakeNativeForLeftovers()
        }
    }

    private fun newFallbackExecutor(): ExecutorService =
        Executors.newSingleThreadExecutor { r ->
            Thread(r, FALLBACK_THREAD_NAME).apply { isDaemon = true }
        }

    /**
     * Drains [pending] on the fallback thread until the queue empties or the
     * native loop takes over ([loopRunning]). If the loop took over mid-drain,
     * any blocks left behind are handed to the first [pump] via
     * [wakeNativeForLeftovers] rather than relying on an incidental
     * `MAIN_EVENTS_CLEARED` tick.
     */
    private fun drainFallback() {
        var ranAnything = false
        while (!loopRunning) {
            val block = pending.poll() ?: break
            ranAnything = true
            @Suppress("TooGenericExceptionCaught", "PrintStackTrace")
            try {
                block.run()
            } catch (t: Throwable) {
                // Match pump(): dispatchers swallow synchronous throwables so a
                // single failing block never kills the drain.
                t.printStackTrace()
            }
        }
        if (ranAnything) {
            Snapshot.sendApplyNotifications()
        }
        wakeNativeForLeftovers()
    }

    /** Drains everything currently pending. New blocks dispatched while
     *  draining run on the next pump (no recursion). */
    fun pump() {
        var ranAnything = false
        // Drain across multiple passes within this single pump so blocks that the
        // running blocks re-dispatch synchronously (the next hops of a coroutine
        // chain) run NOW instead of waiting for the next throttled re-arm. Each
        // pass snapshots the current queue size (no infinite loop on steady churn);
        // the pass count and a wall-clock budget bound a runaway redispatch and let
        // it yield to rendering + the throttled re-arm below.
        val deadlineNs = System.nanoTime() + PUMP_TIME_BUDGET_NS
        var pass = 0
        @Suppress("TooGenericExceptionCaught", "PrintStackTrace", "LoopWithTooManyJumpStatements")
        while (pass++ < MAX_PUMP_PASSES) {
            var remaining = pending.size
            if (remaining == 0) break
            var hitDeadline = false
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
                if (System.nanoTime() >= deadlineNs) {
                    hitDeadline = true
                    break
                }
            }
            if (hitDeadline) break
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
        // by `PUMP_RE_ARM_INTERVAL_NS` instead of waking immediately. External
        // dispatches arriving in the meantime go through the regular
        // [dispatch] path and wake the loop straight away.
        val sinceLast = pumpEndNs - lastPumpNs
        lastPumpNs = pumpEndNs
        if (!pending.isEmpty() &&
            NativeTaoBridge.isLoaded &&
            wakePending.compareAndSet(false, true)
        ) {
            if (sinceLast < PUMP_RE_ARM_INTERVAL_NS) {
                val delayNs = PUMP_RE_ARM_INTERVAL_NS - sinceLast
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
