package dev.nucleusframework.window.tao

import dev.nucleusframework.core.runtime.Platform
import dev.nucleusframework.window.tao.dispatch.TaoMainDispatcher
import dev.nucleusframework.window.tao.ffi.NativeTaoBridge
import java.lang.management.ManagementFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import java.util.logging.Level
import java.util.logging.Logger
import kotlin.concurrent.withLock

/** Milliseconds between two liveness samples. */
private const val POLL_INTERVAL_MS = 2_000L

/** Default extra time a window must stay hung before the watchdog reports it. */
private const val DEFAULT_GRACE_MS = 5_000L

private const val NANOS_PER_MILLI = 1_000_000L

/** A poll that overshot by this much means the machine was suspended, not slow. */
private const val SUSPEND_OVERSHOOT_MS = 10_000L

/** How long samples are ignored after a resume — Electron's `kHungRendererDelay` rule. */
private const val RESUME_GRACE_MS = 30_000L

/** Longest a watchdog parks with nothing to watch; a bound, not a schedule. */
private const val PARK_TIMEOUT_MS = 30_000L

/** An overshoot is a GC pause when collection explains more than this fraction of it. */
private const val GC_PAUSE_SHARE_DIVISOR = 2

/**
 * Test seams for the watchdog's concurrency monkey, all `null` in production.
 *
 * The watchdog is a thread that asks the OS about a real window every two
 * seconds — none of which a race hunt can wait for. With a fake probe and a
 * millisecond poll, the same state machine, lifecycle and callback plumbing run
 * thousands of times a second with no window and no native library, which is
 * what makes `TaoEventLoopWatchdogMonkeyTest` possible.
 */
internal object WatchdogTestHooks {
    /** Replaces the native probe, keyed by the fake HWND the monkey registers. */
    @Volatile
    var probe: ((Long) -> Boolean)? = null

    /** Shortens the poll interval; the real one is [POLL_INTERVAL_MS]. */
    @Volatile
    var pollIntervalMs: Long? = null

    /** Back to production behaviour; a test must always land here. */
    fun reset() {
        probe = null
        pollIntervalMs = null
    }
}

/**
 * Watches the Tao event loop and reports a stall instead of letting the app
 * freeze silently (#643).
 *
 * A deadlocked loop produces no exception, no panic and no error code — to the
 * JVM the thread is a perfectly healthy `RUNNABLE` / `_thread_in_native`, so
 * the fatal path ([TaoApplication.reportFatal]) has nothing to report, and its
 * reporting point sits *after* `nativeRunBlocking` returns, which a stalled
 * loop never does. Only the OS notices, and its only way of saying so is to
 * ghost the window.
 *
 * So the watchdog asks the OS: a daemon thread polls
 * [NativeTaoBridge.nativeIsWindowHung] (`IsHungAppWindow`) every
 * [POLL_INTERVAL_MS] and, once a window has been hung for the grace period on
 * top of the OS's own ~5 s threshold, logs `SEVERE` with every thread's stack
 * — which alone would have pointed straight at `main` sitting in
 * `nativeRunBlocking` for #640.
 *
 * The probe is a pure query of state the OS already maintains: it sends
 * nothing to the event-loop thread, so it costs that thread nothing and cannot
 * inject the inline sent message that caused #640 in the first place.
 *
 * What the app does about it is the app's call, as in Electron: the framework
 * logs and raises [TaoApplication.onUnresponsive] / [TaoApplication.onResponsive]
 * (`unresponsive` / `responsive` on a `webContents`), and ships no UI of its
 * own. The browsers' "wait or quit" dialog is the app's to build — Chromium's
 * HangWatcher, IntelliJ's PerformanceWatcher and Unreal's `FThreadHeartBeat`
 * all stop at the report too.
 *
 * ### Configuration
 * - `nucleus.tao.watchdog=false` — disable entirely (also `true` to force it
 *   on under a debugger, where it is off by default: a breakpoint on the UI
 *   thread is indistinguishable from a stall, which is why Unreal ships its
 *   own hang detector disabled).
 * - `nucleus.tao.watchdogGraceMs=<millis>` — extra time before reporting
 *   (default [DEFAULT_GRACE_MS]).
 * - `nucleus.tao.watchdogDialog=true` — also show the native error dialog on
 *   detection (opt-in: a stall is not always fatal, and the report is a
 *   developer signal first). Shown from the watchdog thread, never from the
 *   event loop — that is precisely the thread that is stuck (#622's
 *   constraint).
 *
 * ### Platforms
 * Windows only for now. macOS exposes no public "not responding" query, and
 * the X11 `_NET_WM_PING` equivalent perturbs the loop it observes — which the
 * probe must not do. Elsewhere the watchdog simply never starts.
 */

@Suppress("TooManyFunctions")
internal object TaoEventLoopWatchdog {
    private val logger = Logger.getLogger(TaoEventLoopWatchdog::class.java.name)

    /** Window handle → HWND, cached from the event-loop thread. */
    private val hwnds = ConcurrentHashMap<Long, Long>()

    private val running = AtomicBoolean(false)

    /** Run counter; a watchdog thread acts only while it owns the current one. */
    private val generations = AtomicInteger()

    /** Guards against stacking one not-responding dialog per stall episode. */
    private val dialogShowing = AtomicBoolean(false)

    /** Wait target of the watchdog thread; signalled when a window appears or on stop. */
    private val lock = ReentrantLock()
    private val wakeUp = lock.newCondition()

    @Volatile
    private var thread: Thread? = null

    /** Runs the app's `unresponsive` / `responsive` callbacks; see [postEvent]. */
    @Volatile
    private var eventExecutor: ExecutorService? = null

    /** `true` on a platform that has a non-perturbing liveness probe. */
    private val isSupported: Boolean
        get() =
            WatchdogTestHooks.probe != null ||
                (Platform.Current == Platform.Windows && NativeTaoBridge.isLoaded)

    private val isEnabled: Boolean
        get() = System.getProperty("nucleus.tao.watchdog", "true").toBoolean()

    /** `true` when the app asked for the watchdog explicitly, debugger or not. */
    private val isForced: Boolean
        get() = System.getProperty("nucleus.tao.watchdog")?.toBoolean() == true

    /**
     * `true` when this JVM runs under a debug agent. A breakpoint on the UI
     * thread is indistinguishable from a stall — Unreal ships its own hang
     * detector off by default for exactly that reason — so the watchdog stays
     * out of debug sessions unless `-Dnucleus.tao.watchdog=true` asks for it.
     * Guarded: `ManagementFactory` is not guaranteed under native-image, where
     * there is no debug agent to find anyway.
     */
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private val isDebuggerAttached: Boolean by lazy {
        try {
            ManagementFactory.getRuntimeMXBean().inputArguments.any {
                it.startsWith("-agentlib:jdwp") || it.startsWith("-Xrunjdwp")
            }
        } catch (t: Throwable) {
            false
        }
    }

    private val pollIntervalMs: Long
        get() = WatchdogTestHooks.pollIntervalMs ?: POLL_INTERVAL_MS

    private val graceMs: Long
        get() = System.getProperty("nucleus.tao.watchdogGraceMs")?.toLongOrNull() ?: DEFAULT_GRACE_MS

    private val showsDialog: Boolean
        get() = System.getProperty("nucleus.tao.watchdogDialog", "false").toBoolean()

    /**
     * Caches [handle]'s HWND so the watchdog thread never has to resolve it
     * later — resolving goes through the native window map, whose lock is
     * exactly what a stalled loop may be holding. Call from the event-loop
     * thread once the window is realized (`WINDOW_READY`).
     */
    fun registerWindow(handle: Long) {
        // `running` covers every off state — unsupported platform, the
        // property, a debug agent, a stopped loop. Off means the event loop
        // pays nothing per window: no JNI round-trip, no signal, no map.
        if (!running.get()) return
        // The monkey registers windows that do not exist; its probe is keyed
        // by the handle itself, so there is nothing native to resolve.
        if (WatchdogTestHooks.probe != null) {
            hwnds[handle] = handle
            wakeWatchdog()
            return
        }
        val hwnd = NativeTaoBridge.nativeHwndHandle(handle)
        if (hwnd == 0L) {
            // Silence here would be the very failure mode this watchdog
            // exists to remove: with no HWND it has nothing to probe.
            logger.warning("Event-loop watchdog: no HWND for window $handle, it will not be watched")
            return
        }
        hwnds[handle] = hwnd
        wakeWatchdog()
    }

    /** Forgets a window that is gone (`DESTROYED`). */
    fun unregisterWindow(handle: Long) {
        hwnds.remove(handle)
    }

    /**
     * Nesting depth of [TaoApplication.expectUnresponsive] blocks. While it is
     * non-zero the loop is *expected* to be unresponsive, so the watchdog
     * treats every sample as healthy — Chromium's
     * `HangWatcher::InvalidateActiveExpectations()`.
     */
    private val expectedStalls = AtomicInteger()

    /** `true` while an [TaoApplication.expectUnresponsive] block is in flight. */
    private val isStallExpected: Boolean
        get() = expectedStalls.get() > 0

    /** Opens an expected-stall scope. */
    fun beginExpectedStall() {
        expectedStalls.incrementAndGet()
    }

    /** Closes an expected-stall scope; never goes below zero. */
    fun endExpectedStall() {
        expectedStalls.updateAndGet { depth -> if (depth > 0) depth - 1 else 0 }
    }

    /** Starts the daemon watchdog thread; no-op when unsupported or disabled. */
    fun start() {
        if (!isSupported || !isEnabled) return
        if (!running.compareAndSet(false, true)) return
        // A scope whose `finally` never ran (a fatal thrown inside
        // `expectUnresponsive`, a forced exit) would otherwise leave the next
        // run permanently disarmed.
        expectedStalls.set(0)
        // `stop()` does not join, so the previous run's thread may still be on
        // its way out. Each run takes a generation and a thread only touches
        // shared state while it owns the current one — otherwise a straggler
        // would disarm the run that just started, or keep sampling beside it
        // and report every stall twice.
        val generation = generations.incrementAndGet()
        thread =
            Thread({ watch(generation) }, "nucleus-tao-watchdog").apply {
                isDaemon = true
                // Below the event loop: the watchdog must never compete with
                // the thread whose health it is measuring.
                priority = Thread.MIN_PRIORITY
                start()
            }
        // The generation this run just took retires every previous thread, but
        // a parked one only learns that when something wakes it.
        wakeWatchdog()
    }

    /** Stops the watchdog and drops the window cache; safe to call twice. */
    fun stop() {
        // Cleanup runs even when `watch()` already cleared `running` itself (a
        // debug agent, an interrupt): `run()` supports being called again, and
        // a second run must not inherit the first one's HWNDs — Windows
        // recycles them, and a non-empty map would also defeat the parking.
        val wasRunning = running.getAndSet(false)
        if (wasRunning) thread?.interrupt()
        thread = null
        hwnds.clear()
        // The stall still open, if any, is closed by the watchdog thread on its
        // way out — it owns its detector, so nobody else has to race it for the
        // right to close the episode.
        wakeWatchdog()
    }

    @Suppress("ReturnCount")
    private fun watch(generation: Int) {
        // Asked here rather than in `start()`: the first
        // `ManagementFactory.getRuntimeMXBean()` call initialises the
        // management subsystem and measures ~6 ms, which `start()` would spend
        // on the main thread with the event loop not yet running. Off the
        // startup path it costs the app nothing.
        if (isDebuggerAttached && !isForced) {
            logger.fine("Event-loop watchdog disabled: a debug agent is attached")
            if (owns(generation)) running.set(false)
            return
        }
        // The detector belongs to this thread. A shared one has to be raced
        // against on every teardown — a straggler could report a stall onto the
        // detector `start()` had just drained, and that episode was then never
        // closed (concurrency monkey, profile Thrash, seed 467221, after 261
        // episodes). Thread-owned, the run that opened an episode is the run
        // that closes it, on whichever path it leaves by.
        val detector = EventLoopHangDetector(graceMs)
        // Not 0: `nanoTime`'s origin is arbitrary and may be negative, and a
        // deadline of 0 would then gate every sample until the clock crossed it.
        var resumeDeadlineNanos = Long.MIN_VALUE
        while (running.get() && owns(generation)) {
            // Stamped around the wait only: a `report()` that takes seconds
            // (a listener uploading, a thread dump on a large app) must not
            // make the next iteration look like a system suspend.
            // The watch list can drain while a stall is still open (the user
            // closed the frozen window). Close the episode before parking, or
            // the app's prompt and telemetry span stay open forever.
            if (hwnds.isEmpty()) guarded { handle(detector.reset(System.nanoTime())) }
            val waitStartNanos = System.nanoTime()
            val gcBefore = gcMillis
            val wait = awaitNextSample(generation, detector)
            if (wait == WatchWait.Interrupted && running.get()) {
                if (!owns(generation)) return drain(detector)
                // Interrupted by something other than `stop()` — a shutdown
                // hook or a test harness sweeping threads. Leave, but leave
                // the door open: `running` stays consistent so a later
                // `start()` can bring the watchdog back, and say so once.
                logger.warning("Event-loop watchdog stopped: its thread was interrupted")
                running.set(false)
                return drain(detector)
            }
            if (wait == WatchWait.Stopped || wait == WatchWait.Interrupted || !running.get()) {
                return drain(detector)
            }
            val now = System.nanoTime()
            // An untimed park tells nothing about elapsed time, so the suspend
            // heuristic below would read it as one. Re-baseline and sample on
            // the next tick instead.
            if (wait == WatchWait.Parked) continue
            val overslept = now - waitStartNanos - pollIntervalMs * NANOS_PER_MILLI
            // The machine was suspended (Electron #53529): every process
            // stopped, and on wake the window is briefly flagged while the
            // system pages back in. A sleep that overshot by far is the only
            // signal a plain JVM gets — `base::PowerMonitor` without the
            // platform hookup. Drop the episode and ignore what follows for
            // one hang delay, exactly as Electron does after a resume.
            resumeDeadlineNanos = step(detector, now, overslept, gcMillis - gcBefore, resumeDeadlineNanos)
        }
        drain(detector)
    }

    /**
     * Closes the episode this thread opened, on whatever path it is leaving by:
     * an app holding a prompt or a telemetry span on the strength of
     * `unresponsive` must always hear the end.
     */
    private fun drain(detector: EventLoopHangDetector) {
        guarded { handle(detector.reset(System.nanoTime())) }
    }

    /**
     * One sample and its consequences, guarded: `Thread.getAllStackTraces()`
     * can fail on a huge heap, JUL propagates a throwing `Handler.publish`
     * (apps and our own tests attach handlers), and the event executor can
     * refuse a task. Any of those escaping would kill the watchdog thread with
     * `running` still true — unrevivable, and silent, which is precisely the
     * failure mode this class exists to remove. Returns the resume deadline.
     */
    @Suppress("TooGenericExceptionCaught")
    private fun step(
        detector: EventLoopHangDetector,
        now: Long,
        oversleptNanos: Long,
        gcMillisDuringWait: Long,
        resumeDeadlineNanos: Long,
    ): Long {
        try {
            if (oversleptNanos > SUSPEND_OVERSHOOT_MS * NANOS_PER_MILLI &&
                !isGcPause(gcMillisDuringWait, oversleptNanos)
            ) {
                // A stall reported before the suspend still gets its recovery:
                // an app that opened a telemetry span or a prompt on
                // `unresponsive` must never be left waiting for the close.
                handle(detector.reset(now))
                return now + RESUME_GRACE_MS * NANOS_PER_MILLI
            }
            if (now >= resumeDeadlineNanos) {
                // An expected stall counts as healthy rather than skipping the
                // sample: a stall reported before the scope opened still gets
                // its recovery, so every `unresponsive` keeps its `responsive`.
                handle(detector.sample(!isStallExpected && isAnyWindowHung(), now))
            }
        } catch (t: Throwable) {
            logSafely(t)
        }
        return resumeDeadlineNanos
    }

    /** `true` while this thread is the run's current watchdog — see [start]. */
    private fun owns(generation: Int): Boolean = generations.get() == generation

    /** Runs [block], swallowing anything it throws — see [step] for why. */
    @Suppress("TooGenericExceptionCaught")
    private inline fun guarded(block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            logSafely(t)
        }
    }

    /** Last-resort logging: the failure of a log call must not end the watch. */
    @Suppress("TooGenericExceptionCaught", "EmptyCatchBlock", "SwallowedException")
    private fun logSafely(t: Throwable) {
        try {
            logger.log(Level.WARNING, "Event-loop watchdog sample failed; still watching", t)
        } catch (_: Throwable) {
            // Nothing left to report with. Keep watching.
        }
    }

    /**
     * `true` when a stop-the-world pause, not a suspended machine, explains an
     * overshot wait. The watchdog is an ordinary min-priority Java thread, so a
     * long full GC parks it too — and a GC long enough to freeze the UI is one
     * of the freezes most worth reporting. Treating it as a resume would drop
     * the very episode the user felt.
     */
    private fun isGcPause(
        gcMillisDuringWait: Long,
        oversleptNanos: Long,
    ): Boolean = gcMillisDuringWait * NANOS_PER_MILLI * GC_PAUSE_SHARE_DIVISOR > oversleptNanos

    /**
     * Total time this JVM has spent collecting, or 0 when the management beans
     * are unavailable (possible under native-image), which keeps the plain
     * suspend rule.
     */
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private val gcMillis: Long
        get() =
            try {
                ManagementFactory.getGarbageCollectorMXBeans().sumOf { it.collectionTime.coerceAtLeast(0) }
            } catch (t: Throwable) {
                0L
            }

    /**
     * Waits for the next sample. With no window registered there is nothing to
     * probe and nothing can hang, so the thread parks until one appears rather
     * than waking every [POLL_INTERVAL_MS] — Chromium's HangWatcher parks the
     * same way while its watch list is empty, and it is what keeps an app that
     * is merely sitting in the tray free of a timer it does not need.
     */
    private fun awaitNextSample(
        generation: Int,
        detector: EventLoopHangDetector,
    ): WatchWait =
        lock.withLock {
            try {
                // Re-checked here, under the lock the signal is sent with: a
                // thread that read these outside it could decide to park an
                // instant after the last `signalAll` and never be woken again.
                // The concurrency monkey found 150 such threads alive at once
                // (profile Thrash) — one leaked per run, for the process's life.
                if (!running.get() || !owns(generation)) return@withLock WatchWait.Stopped
                // Never park on an open episode. The drain above this call
                // runs outside the lock, so the last window can be unregistered
                // in between — and parking then holds the app's `responsive`
                // for the whole park. One more timed wait closes it instead.
                if (hwnds.isEmpty() && !detector.hasOpenEpisode) {
                    // Bounded even so: a missed signal must cost one late
                    // wakeup, never a thread that never leaves.
                    wakeUp.await(PARK_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                    if (running.get()) WatchWait.Parked else WatchWait.Stopped
                } else {
                    wakeUp.await(pollIntervalMs, TimeUnit.MILLISECONDS)
                    WatchWait.Sampled
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                WatchWait.Interrupted
            }
        }

    /** Wakes a parked watchdog — a window appeared, or the loop is shutting down. */
    private fun wakeWatchdog() {
        lock.withLock { wakeUp.signalAll() }
    }

    /** Outcome of one [awaitNextSample] wait. */
    private enum class WatchWait {
        /** Waited the poll interval: the elapsed time is known, so sample. */
        Sampled,

        /** Parked with nothing to watch: elapsed time means nothing. */
        Parked,

        /** The watchdog was stopped. */
        Stopped,

        /** The wait was interrupted; only [stop] is a legitimate source. */
        Interrupted,
    }

    private fun handle(transition: HangTransition?) {
        when (transition) {
            is HangTransition.Stalled -> report(transition.durationMs)
            is HangTransition.Recovered -> {
                // Same order as [report], for the same reason.
                postEvent(TaoApplication::notifyResponsive)
                guarded {
                    logger.log(Level.INFO, "Tao event loop responded again after ${transition.durationMs} ms")
                }
            }
            null -> Unit
        }
    }

    /**
     * `true` when at least one live window is hung. Any single one is enough:
     * every window of the app shares the one event-loop thread, so a stall on
     * one is the stall of all — and a window whose HWND is already gone simply
     * probes healthy.
     */
    private fun isAnyWindowHung(): Boolean {
        val probe = WatchdogTestHooks.probe ?: NativeTaoBridge::nativeIsWindowHung
        return hwnds.values.any(probe)
    }

    private fun report(durationMs: Long) {
        // The app hears first, and unconditionally. Logging came first here
        // until the concurrency monkey (seed 4242) caught what that costs: JUL
        // propagates a throwing `Handler.publish`, so a hostile log handler
        // skipped the notification while the detector had already marked the
        // stall reported — the app then got a `responsive` for a stall it was
        // never told about. Diagnostics must never outrank the contract.
        //
        // Off the watchdog thread: the documented use of this callback is a
        // "wait or quit" prompt, which blocks until the user answers. Run
        // inline it would stop the sampling loop for the whole episode — no
        // recovery, no `onResponsive`, the next stall missed.
        postEvent(TaoApplication::notifyUnresponsive)
        val detail = runCatching { allThreadStacks() }.getOrElse { "thread dump unavailable: $it" }
        guarded {
            logger.log(
                Level.SEVERE,
                "Tao event loop has not pumped messages for at least $durationMs ms — the UI is frozen. " +
                    "Thread dump follows.\n$detail",
            )
        }
        if (showsDialog) showNotRespondingDialog(detail)
    }

    /**
     * Runs an app callback on the event thread, created on first use. One
     * thread, so `unresponsive` and `responsive` keep their order; a listener
     * that blocks delays the next callback but never the detection.
     */
    private fun postEvent(event: () -> Unit) {
        // One per process, created on the first event and never shut down: a
        // daemon thread parked on an empty queue costs nothing, while tearing it
        // down per run meant racing its teardown and dropping the very callback
        // that closes an episode.
        val executor =
            lock.withLock {
                eventExecutor ?: Executors
                    .newSingleThreadExecutor { runnable ->
                        Thread(runnable, "nucleus-tao-watchdog-events").apply { isDaemon = true }
                    }.also { eventExecutor = it }
            }
        executor.execute(event)
    }

    /**
     * Opens the native dialog on a thread of its own. Not on the event loop —
     * that is the stuck thread (#622's constraint) — but not on the watchdog
     * thread either: the dialog blocks until dismissed, and a watchdog parked
     * in it stops sampling, so the recovery would only be noticed (and
     * [TaoApplication.onResponsive] only fire) once the user clicked OK.
     */
    private fun showNotRespondingDialog(detail: String) {
        // One at a time, like `fatalDialogShown`: an app stalling repeatedly
        // would otherwise leave a pile of modals for the user to dismiss.
        if (!dialogShowing.compareAndSet(false, true)) return
        Thread(
            {
                showNativeErrorDialog(
                    title = "Application Not Responding",
                    message = "The user interface has stopped responding.",
                    detail = detail,
                )
                dialogShowing.set(false)
            },
            "nucleus-tao-watchdog-dialog",
        ).apply { isDaemon = true }.start()
    }

    /**
     * Every thread's stack, the event-loop thread first — it is the one under
     * suspicion, and the reader should not have to hunt for it.
     */
    private fun allThreadStacks(): String {
        val loopThread = TaoMainDispatcher.taoMainThread
        return Thread
            .getAllStackTraces()
            .entries
            .sortedByDescending { it.key === loopThread }
            .joinToString("\n\n") { (thread, stack) ->
                val marker = if (thread === loopThread) " (Tao event loop)" else ""
                buildString {
                    append("\"").append(thread.name).append("\"").append(marker)
                    append(" ").append(thread.state)
                    stack.forEach { append("\n\tat ").append(it) }
                }
            }
    }
}

/** What a liveness sample means for the watchdog, or `null` for "no change". */
internal sealed interface HangTransition {
    /** The loop has been hung past the grace period; reported once per stall. */
    data class Stalled(
        val durationMs: Long,
    ) : HangTransition

    /** The loop pumped again after a reported stall. */
    data class Recovered(
        val durationMs: Long,
    ) : HangTransition
}

/**
 * Turns a stream of "is it hung?" samples into at most one
 * [HangTransition.Stalled] per stall and one [HangTransition.Recovered] when it
 * ends. Separate from the polling thread so the state machine is testable
 * without a window, a native library or wall-clock waiting.
 *
 * The OS flag already means "~5 s without pumping"; [graceMs] is the extra time
 * on top of it, which keeps a merely slow frame — a long synchronous operation
 * that does come back — out of the log.
 */
internal class EventLoopHangDetector(
    private val graceMs: Long,
) {
    // Nullable rather than a 0 sentinel: `System.nanoTime` has an arbitrary
    // origin and 0 is one of its legal readings.
    private var hangStartNanos: Long? = null
    private var reported = false

    /**
     * `true` once a stall has been reported and not yet closed. The watchdog
     * reads it to decide whether it may park: parking on an open episode would
     * hold the app's `responsive` for the length of the park.
     */
    val hasOpenEpisode: Boolean
        get() = reported

    /** Feeds one sample taken at [nowNanos] (a [System.nanoTime] reading). */
    fun sample(
        hung: Boolean,
        nowNanos: Long,
    ): HangTransition? {
        if (!hung) {
            val since = hangStartNanos.takeIf { reported }
            hangStartNanos = null
            reported = false
            return since?.let { HangTransition.Recovered(millisSince(it, nowNanos)) }
        }
        val start = hangStartNanos ?: nowNanos.also { hangStartNanos = it }
        if (reported) return null
        val duration = millisSince(start, nowNanos)
        if (duration < graceMs) return null
        reported = true
        return HangTransition.Stalled(duration)
    }

    /**
     * Forgets the episode in flight — for samples that cannot be trusted at
     * all, such as the ones straddling a system suspend.
     *
     * Returns a [HangTransition.Recovered] when a stall had already been
     * reported: the duration is a lower bound (the suspend swallowed the rest),
     * but an app that opened a prompt or a telemetry span on the report must
     * get its close, so every `unresponsive` keeps its `responsive`.
     */
    fun reset(nowNanos: Long): HangTransition? {
        val since = hangStartNanos.takeIf { reported }
        hangStartNanos = null
        reported = false
        return since?.let { HangTransition.Recovered(millisSince(it, nowNanos)) }
    }

    private fun millisSince(
        startNanos: Long,
        nowNanos: Long,
    ): Long = (nowNanos - startNanos) / NANOS_PER_MILLI
}
