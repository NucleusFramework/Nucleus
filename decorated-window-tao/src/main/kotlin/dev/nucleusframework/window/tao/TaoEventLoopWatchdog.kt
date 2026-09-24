package dev.nucleusframework.window.tao

import dev.nucleusframework.core.runtime.Platform
import dev.nucleusframework.window.tao.dispatch.TaoMainDispatcher
import dev.nucleusframework.window.tao.ffi.NativeTaoBridge
import java.lang.management.ManagementFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.logging.Level
import java.util.logging.Logger

/** Milliseconds between two liveness samples. */
private const val POLL_INTERVAL_MS = 2_000L

/** Default extra time a window must stay hung before the watchdog reports it. */
private const val DEFAULT_GRACE_MS = 5_000L

private const val NANOS_PER_MILLI = 1_000_000L

/** A poll that overshot by this much means the machine was suspended, not slow. */
private const val SUSPEND_OVERSHOOT_MS = 10_000L

/** How long samples are ignored after a resume — Electron's `kHungRendererDelay` rule. */
private const val RESUME_GRACE_MS = 30_000L

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
internal object TaoEventLoopWatchdog {
    private val logger = Logger.getLogger(TaoEventLoopWatchdog::class.java.name)

    /** Window handle → HWND, cached from the event-loop thread. */
    private val hwnds = ConcurrentHashMap<Long, Long>()

    private val running = AtomicBoolean(false)

    @Volatile
    private var thread: Thread? = null

    /** `true` on a platform that has a non-perturbing liveness probe. */
    private val isSupported: Boolean
        get() = Platform.Current == Platform.Windows && NativeTaoBridge.isLoaded

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
        if (!isSupported) return
        val hwnd = NativeTaoBridge.nativeHwndHandle(handle)
        if (hwnd != 0L) hwnds[handle] = hwnd
    }

    /** Forgets a window that is gone (`DESTROYED`). */
    fun unregisterWindow(handle: Long) {
        hwnds.remove(handle)
    }

    /** Starts the daemon watchdog thread; no-op when unsupported or disabled. */
    fun start() {
        if (!isSupported || !isEnabled) return
        if (isDebuggerAttached && !isForced) {
            logger.fine("Event-loop watchdog disabled: a debug agent is attached")
            return
        }
        if (!running.compareAndSet(false, true)) return
        thread =
            Thread(::watch, "nucleus-tao-watchdog").apply {
                isDaemon = true
                // Below the event loop: the watchdog must never compete with
                // the thread whose health it is measuring.
                priority = Thread.MIN_PRIORITY
                start()
            }
    }

    /** Stops the watchdog and drops the window cache; safe to call twice. */
    fun stop() {
        if (!running.compareAndSet(true, false)) return
        thread?.interrupt()
        thread = null
        hwnds.clear()
    }

    private fun watch() {
        val detector = EventLoopHangDetector(graceMs)
        var lastSampleNanos = System.nanoTime()
        var resumeDeadlineNanos = 0L
        while (running.get() && sleepUntilNextSample()) {
            if (!running.get()) return
            val now = System.nanoTime()
            val overslept = now - lastSampleNanos - POLL_INTERVAL_MS * NANOS_PER_MILLI
            lastSampleNanos = now
            // The machine was suspended (Electron #53529): every process
            // stopped, and on wake the window is briefly flagged while the
            // system pages back in. A sleep that overshot by far is the only
            // signal a plain JVM gets — `base::PowerMonitor` without the
            // platform hookup. Drop the episode and ignore what follows for
            // one hang delay, exactly as Electron does after a resume.
            if (overslept > SUSPEND_OVERSHOOT_MS * NANOS_PER_MILLI) {
                detector.reset()
                resumeDeadlineNanos = now + RESUME_GRACE_MS * NANOS_PER_MILLI
            } else if (now >= resumeDeadlineNanos) {
                handle(detector.sample(isAnyWindowHung(), now))
            }
        }
    }

    /** Sleeps one poll interval; `false` once the watchdog has been stopped. */
    private fun sleepUntilNextSample(): Boolean =
        try {
            Thread.sleep(POLL_INTERVAL_MS)
            true
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }

    private fun handle(transition: HangTransition?) {
        when (transition) {
            is HangTransition.Stalled -> report(transition.durationMs)
            is HangTransition.Recovered -> {
                logger.log(Level.INFO, "Tao event loop responded again after ${transition.durationMs} ms")
                TaoApplication.notifyResponsive()
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
    private fun isAnyWindowHung(): Boolean = hwnds.values.any { NativeTaoBridge.nativeIsWindowHung(it) }

    private fun report(durationMs: Long) {
        val detail = allThreadStacks()
        logger.log(
            Level.SEVERE,
            "Tao event loop has not pumped messages for at least $durationMs ms — the UI is frozen. " +
                "Thread dump follows.\n$detail",
        )
        // Hand the event to the app before anything blocking: a listener that
        // reports to a crash backend must not queue behind a modal dialog
        // nobody is there to dismiss.
        TaoApplication.notifyUnresponsive()
        if (showsDialog) showNotRespondingDialog(detail)
    }

    /**
     * Opens the native dialog on a thread of its own. Not on the event loop —
     * that is the stuck thread (#622's constraint) — but not on the watchdog
     * thread either: the dialog blocks until dismissed, and a watchdog parked
     * in it stops sampling, so the recovery would only be noticed (and
     * [TaoApplication.onResponsive] only fire) once the user clicked OK.
     */
    private fun showNotRespondingDialog(detail: String) {
        Thread(
            {
                showNativeErrorDialog(
                    title = "Application Not Responding",
                    message = "The user interface has stopped responding.",
                    detail = detail,
                )
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
     * Forgets the episode in flight without emitting anything — for samples
     * that cannot be trusted at all, such as the ones straddling a system
     * suspend. A stall already reported is dropped silently rather than closed
     * with a recovery: nothing was observed between the two samples, so there
     * is nothing to claim about it.
     */
    fun reset() {
        hangStartNanos = null
        reported = false
    }

    private fun millisSince(
        startNanos: Long,
        nowNanos: Long,
    ): Long = (nowNanos - startNanos) / NANOS_PER_MILLI
}
