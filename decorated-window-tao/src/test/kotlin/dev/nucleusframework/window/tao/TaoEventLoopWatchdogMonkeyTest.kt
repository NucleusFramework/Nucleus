package dev.nucleusframework.window.tao

import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.logging.Handler
import java.util.logging.LogRecord
import java.util.logging.Logger
import kotlin.concurrent.thread
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.fail

/** Replays a red run: `-Dnucleus.tao.watchdogMonkeySeed=<seed>`. */
private const val SEED_PROPERTY = "nucleus.tao.watchdogMonkeySeed"

/** Restricts a run to one profile: `-Dnucleus.tao.watchdogMonkeyProfile=Hostile`. */
private const val PROFILE_PROPERTY = "nucleus.tao.watchdogMonkeyProfile"

/** Sweeps N seeds in one JVM: `-Dnucleus.tao.watchdogMonkeySeeds=200`. */
private const val SEED_COUNT_PROPERTY = "nucleus.tao.watchdogMonkeySeeds"

/** Fixed so a green run stays green; override the properties to explore. */
private val DEFAULT_SEEDS = listOf(1L, 4_242L, 20_260_924L)

private const val QUIESCE_TIMEOUT_MS = 8_000L
private const val REARM_TIMEOUT_MS = 8_000L
private const val JOURNAL_DEPTH = 48
private const val WORKER_JOIN_TIMEOUT_MS = 60_000L

/**
 * Concurrency monkey for the hang watchdog (#643) — the deliberately vicious
 * one.
 *
 * Four rounds of review found five lifecycle races by reading; the first run of
 * this test found a sixth by playing (a throwing JUL handler swallowed
 * `onUnresponsive` while the detector had already marked the stall reported, so
 * the app got a `responsive` for a stall it never heard about). The profiles
 * below exist to keep finding that class of thing: they hammer the watchdog
 * from several threads at once with a fake probe and a sub-millisecond poll
 * ([WatchdogTestHooks]), including the moves an app really does make and that
 * nothing else in the suite covers — calling back into `start` / `stop` /
 * `expectUnresponsive` **from inside a callback**, and interrupting the
 * watchdog thread the way a shutdown hook sweeping threads by name would.
 *
 * It asserts nothing about *what* happened — for a random sequence there is no
 * right answer — only that nothing wedges and nothing is left behind:
 *
 * 1. the storm terminates (a join timeout dumps every stack: that is the
 *    deadlock detector, and the lock the watchdog took to make teardown safe is
 *    exactly what could produce one),
 * 2. nothing escapes the watchdog's surface, whatever a listener, a log handler
 *    or a sample throws,
 * 3. every `unresponsive` is eventually paired with a `responsive`,
 * 4. no watchdog thread is left alive,
 * 5. and the watchdog still reports afterwards — what the generation token is
 *    for.
 *
 * A failure prints the profile, the seed and the last actions;
 * `-D$SEED_PROPERTY` and `-D$PROFILE_PROPERTY` replay it.
 */
class TaoEventLoopWatchdogMonkeyTest {
    /**
     * Cumulative across every storm, on purpose: an event queued before a
     * `stop()` is delivered *after* it, to whatever handler is installed by
     * then — so the pairing invariant only means anything process-wide. Per
     * storm it would flag the queue's own latency as a lost event.
     */
    private val unresponsive = AtomicInteger()
    private val responsive = AtomicInteger()

    @AfterTest
    fun tearDown() {
        TaoEventLoopWatchdog.stop()
        WatchdogTestHooks.reset()
        System.clearProperty("nucleus.tao.watchdogGraceMs")
        System.clearProperty("nucleus.tao.watchdog")
    }

    @Test
    fun `lifecycle storms leave the watchdog armed and every stall closed`() {
        val seeds =
            System.getProperty(SEED_PROPERTY)?.toLongOrNull()?.let { listOf(it) }
                // A sweep: hundreds of storms in one JVM, which is the only way
                // to reach the interleavings a handful of seeds never hit.
                ?: System.getProperty(SEED_COUNT_PROPERTY)?.toIntOrNull()?.let { count ->
                    (1..count).map { it * SWEEP_STRIDE }
                }
                ?: DEFAULT_SEEDS
        val profiles =
            System.getProperty(PROFILE_PROPERTY)?.let { name ->
                listOf(MonkeyProfile.valueOf(name))
            } ?: MonkeyProfile.entries
        profiles.forEach { profile -> seeds.forEach { seed -> storm(profile, seed) } }
    }

    @Suppress("LongMethod", "CyclomaticComplexMethod") // one flat storm: setup, workers, invariants
    private fun storm(
        profile: MonkeyProfile,
        seed: Long,
    ) {
        val ctx = StormContext(hung = AtomicBoolean(false), unresponsive = unresponsive, responsive = responsive)
        val journal = ConcurrentLinkedDeque<String>()
        val failures = ConcurrentLinkedDeque<Throwable>()

        WatchdogTestHooks.probe = { ctx.hung.get() }
        WatchdogTestHooks.pollIntervalMs = profile.pollMs
        System.setProperty("nucleus.tao.watchdogGraceMs", profile.graceMs.toString())
        // Forced: a test JVM may itself run under a debug agent, which the
        // watchdog otherwise (correctly) stays out of.
        System.setProperty("nucleus.tao.watchdog", "true")
        ctx.installCountingHandlers()

        // A log handler that throws. JUL propagates that into the watchdog's own
        // reporting path, which must survive it — and must not let it swallow
        // the app's notification.
        val watchdogLogger = Logger.getLogger(TaoEventLoopWatchdog::class.java.name)
        val hostileHandler = HostileLogHandler(profile.hostileLogEvery)
        watchdogLogger.addHandler(hostileHandler)

        val start = CountDownLatch(1)
        val workers =
            (0 until profile.workers).map { worker ->
                thread(name = "watchdog-monkey-$worker", isDaemon = true) {
                    val random = Random(seed * PRIME + worker)
                    start.await()
                    repeat(profile.ops) { step ->
                        val action = profile.pick(random)
                        journal.addLast("w$worker#$step $action")
                        while (journal.size > JOURNAL_DEPTH) journal.pollFirst()
                        try {
                            action.run(random, ctx)
                        } catch (t: Throwable) {
                            // The whole point: nothing the monkey does may throw
                            // out of the watchdog's public surface.
                            failures.addLast(t)
                        }
                    }
                }
            }
        start.countDown()

        fun bail(reason: String): Nothing =
            fail(
                buildString {
                    appendLine(reason)
                    appendLine("  profile: $profile, seed: $seed")
                    appendLine("  replay: -D$PROFILE_PROPERTY=$profile -D$SEED_PROPERTY=$seed")
                    appendLine("  unresponsive=${ctx.unresponsive.get()} responsive=${ctx.responsive.get()}")
                    appendLine("  last ${journal.size} actions:")
                    journal.forEach { appendLine("    $it") }
                    failures.take(FAILURES_SHOWN).forEach { appendLine("    threw: $it") }
                },
            )

        // 1 — the storm terminates. A join that times out is a deadlock until
        // proven otherwise, and the stacks are the only thing that can say
        // which lock it was.
        val deadline = System.currentTimeMillis() + WORKER_JOIN_TIMEOUT_MS
        workers.forEach { worker ->
            val left = deadline - System.currentTimeMillis()
            if (left > 0) worker.join(left)
            if (worker.isAlive) {
                val stacks =
                    Thread
                        .getAllStackTraces()
                        .entries
                        .filter { (t, _) -> t.name.startsWith("watchdog-monkey") || t.name.startsWith("nucleus-tao") }
                        .joinToString("\n\n") { (t, stack) ->
                            "\"${t.name}\" ${t.state}" + stack.joinToString("") { "\n\tat $it" }
                        }
                bail("the storm wedged — ${worker.name} still alive after ${WORKER_JOIN_TIMEOUT_MS}ms\n$stacks")
            }
        }

        // Settle: clean handlers again (the storm installs throwing ones), a
        // healthy loop, and a live watchdog to close whatever is still open.
        ctx.installCountingHandlers()
        ctx.hung.set(false)
        TaoEventLoopWatchdog.start()
        TaoEventLoopWatchdog.registerWindow(SETTLE_WINDOW)
        awaitQuiet(ctx, profile)
        TaoEventLoopWatchdog.stop()
        awaitQuiet(ctx, profile)
        watchdogLogger.removeHandler(hostileHandler)

        if (failures.isNotEmpty()) bail("the watchdog's surface threw ${failures.size} time(s)")

        // 3 — pairing. An app holding a prompt or a telemetry span on
        // `unresponsive` must always hear the end of the episode.
        if (ctx.unresponsive.get() != ctx.responsive.get()) bail("unresponsive/responsive left unpaired")

        // 4 — nothing left behind.
        val leaked = liveWatchdogThreads()
        if (leaked.isNotEmpty()) bail("watchdog threads still alive after stop: $leaked")

        // 5 — still armed. The storm's start/stop interleavings are exactly what
        // let a straggler disarm the next run before the generation token.
        val rearmed = AtomicInteger()
        // Counts into the shared tally too: the pairing invariant spans the
        // whole test, and the recovery of *this* stall lands in it.
        TaoApplication.onUnresponsive {
            unresponsive.incrementAndGet()
            rearmed.incrementAndGet()
        }
        TaoEventLoopWatchdog.start()
        TaoEventLoopWatchdog.registerWindow(SETTLE_WINDOW)
        ctx.hung.set(true)
        val rearmDeadline = System.currentTimeMillis() + REARM_TIMEOUT_MS
        while (rearmed.get() == 0 && System.currentTimeMillis() < rearmDeadline) Thread.sleep(profile.pollMs)
        ctx.hung.set(false)
        TaoEventLoopWatchdog.stop()
        if (rearmed.get() == 0) bail("the watchdog no longer reports after the storm")
    }

    /** Waits until the counters stop moving and agree, or lets the assertions speak. */
    private fun awaitQuiet(
        ctx: StormContext,
        profile: MonkeyProfile,
    ) {
        val deadline = System.currentTimeMillis() + QUIESCE_TIMEOUT_MS
        var last = -1 to -1
        var stableSince = System.currentTimeMillis()
        while (System.currentTimeMillis() < deadline) {
            val now = ctx.unresponsive.get() to ctx.responsive.get()
            if (now != last) {
                last = now
                stableSince = System.currentTimeMillis()
            } else if (System.currentTimeMillis() - stableSince > QUIET_MS && now.first == now.second) {
                return
            }
            Thread.sleep(profile.pollMs)
        }
    }

    private fun liveWatchdogThreads(): List<String> =
        Thread
            .getAllStackTraces()
            .keys
            .filter { it.isAlive && it.name == "nucleus-tao-watchdog" }
            .map { it.name }

    /** Shared state of one storm: the fake loop's health and the paired counters. */
    private class StormContext(
        val hung: AtomicBoolean,
        val unresponsive: AtomicInteger,
        val responsive: AtomicInteger,
    ) {
        fun installCountingHandlers() {
            TaoApplication.onUnresponsive { unresponsive.incrementAndGet() }
            TaoApplication.onResponsive { responsive.incrementAndGet() }
        }

        /** Counts, then throws: pairing still holds, and the watchdog must survive. */
        fun installHostileHandler() {
            TaoApplication.onUnresponsive {
                unresponsive.incrementAndGet()
                error("hostile listener")
            }
        }

        /**
         * A listener that drives the watchdog from inside its own callback —
         * an app whose crash reporter tears the run down on a hang. Reentrancy
         * on the event thread, which is where a lock-ordering mistake shows up.
         */
        fun installReentrantHandler(random: Random) {
            TaoApplication.onUnresponsive {
                unresponsive.incrementAndGet()
                when (random.nextInt(REENTRANT_MOVES)) {
                    0 -> TaoEventLoopWatchdog.stop()
                    1 -> TaoEventLoopWatchdog.start()
                    2 -> TaoApplication.expectUnresponsive { TaoEventLoopWatchdog.registerWindow(SETTLE_WINDOW) }
                    else -> TaoEventLoopWatchdog.unregisterWindow(SETTLE_WINDOW)
                }
            }
        }
    }

    private class HostileLogHandler(
        private val every: Int,
    ) : Handler() {
        private val records = AtomicInteger()

        override fun publish(record: LogRecord) {
            if (every > 0 && records.incrementAndGet() % every == 0) error("hostile log handler")
        }

        override fun flush() = Unit

        override fun close() = Unit
    }

    /**
     * How mean a storm is. Each profile leans on a different failure mode; they
     * all run every seed.
     */
    private enum class MonkeyProfile(
        val workers: Int,
        val ops: Int,
        val pollMs: Long,
        val graceMs: Long,
        val hostileLogEvery: Int,
        val actions: List<MonkeyAction>,
    ) {
        /** Everything, evenly. */
        Balanced(
            workers = 4,
            ops = 400,
            pollMs = 2,
            graceMs = 4,
            hostileLogEvery = 8,
            actions = MonkeyAction.entries,
        ),

        /** Nothing but lifecycle: the restart race, as hard as threads allow. */
        Thrash(
            workers = 8,
            ops = 600,
            pollMs = 1,
            graceMs = 1,
            hostileLogEvery = 0,
            actions = listOf(MonkeyAction.Start, MonkeyAction.Stop, MonkeyAction.RegisterWindow),
        ),

        /** The probe flips constantly: episodes open and close on top of each other. */
        Flapping(
            workers = 6,
            ops = 600,
            pollMs = 1,
            graceMs = 0,
            hostileLogEvery = 16,
            actions =
                listOf(
                    MonkeyAction.Freeze,
                    MonkeyAction.Thaw,
                    MonkeyAction.RegisterWindow,
                    MonkeyAction.UnregisterWindow,
                    MonkeyAction.Start,
                    MonkeyAction.Stop,
                ),
        ),

        /** Everything throws, and the callbacks call back in. */
        Hostile(
            workers = 6,
            ops = 400,
            pollMs = 1,
            graceMs = 1,
            hostileLogEvery = 2,
            actions =
                listOf(
                    MonkeyAction.HostileListener,
                    MonkeyAction.ReentrantListener,
                    MonkeyAction.ExpectStallThatThrows,
                    MonkeyAction.Freeze,
                    MonkeyAction.Start,
                    MonkeyAction.Stop,
                    MonkeyAction.RegisterWindow,
                    MonkeyAction.CleanListener,
                ),
        ),

        /** Someone else's shutdown hook interrupts threads by name. */
        Interrupted(
            workers = 4,
            ops = 300,
            pollMs = 1,
            graceMs = 1,
            hostileLogEvery = 8,
            actions =
                listOf(
                    MonkeyAction.InterruptWatchdog,
                    MonkeyAction.Start,
                    MonkeyAction.Stop,
                    MonkeyAction.RegisterWindow,
                    MonkeyAction.Freeze,
                    MonkeyAction.Thaw,
                ),
        ),
        ;

        fun pick(random: Random): MonkeyAction = actions[random.nextInt(actions.size)]
    }

    /** One move of the storm. Every one of them is legal API use. */
    private enum class MonkeyAction {
        Start {
            override fun run(
                random: Random,
                ctx: StormContext,
            ) = TaoEventLoopWatchdog.start()
        },
        Stop {
            override fun run(
                random: Random,
                ctx: StormContext,
            ) = TaoEventLoopWatchdog.stop()
        },
        RegisterWindow {
            override fun run(
                random: Random,
                ctx: StormContext,
            ) = TaoEventLoopWatchdog.registerWindow(random.nextLong(1, WINDOW_HANDLES))
        },
        UnregisterWindow {
            override fun run(
                random: Random,
                ctx: StormContext,
            ) = TaoEventLoopWatchdog.unregisterWindow(random.nextLong(1, WINDOW_HANDLES))
        },
        Freeze {
            override fun run(
                random: Random,
                ctx: StormContext,
            ) = ctx.hung.set(true)
        },
        Thaw {
            override fun run(
                random: Random,
                ctx: StormContext,
            ) = ctx.hung.set(false)
        },
        ExpectStall {
            override fun run(
                random: Random,
                ctx: StormContext,
            ) {
                TaoApplication.expectUnresponsive { Thread.sleep(random.nextLong(0, 3)) }
            }
        },
        ExpectStallThatThrows {
            override fun run(
                random: Random,
                ctx: StormContext,
            ) {
                // The scope must unwind even when the work explodes, or the next
                // run starts permanently disarmed.
                runCatching { TaoApplication.expectUnresponsive<Unit> { error("boom") } }
            }
        },
        HostileListener {
            override fun run(
                random: Random,
                ctx: StormContext,
            ) = ctx.installHostileHandler()
        },
        ReentrantListener {
            override fun run(
                random: Random,
                ctx: StormContext,
            ) = ctx.installReentrantHandler(random)
        },
        CleanListener {
            override fun run(
                random: Random,
                ctx: StormContext,
            ) = ctx.installCountingHandlers()
        },
        InterruptWatchdog {
            override fun run(
                random: Random,
                ctx: StormContext,
            ) {
                // What a shutdown hook sweeping threads by name does to us.
                Thread
                    .getAllStackTraces()
                    .keys
                    .filter { it.name.startsWith("nucleus-tao-watchdog") }
                    .forEach { it.interrupt() }
            }
        },
        Breathe {
            override fun run(
                random: Random,
                ctx: StormContext,
            ) = Thread.sleep(random.nextLong(0, 4))
        }, ;

        abstract fun run(
            random: Random,
            ctx: StormContext,
        )
    }

    private companion object {
        const val WINDOW_HANDLES = 6L
        const val SETTLE_WINDOW = 99L
        const val QUIET_MS = 200L
        const val FAILURES_SHOWN = 3
        const val REENTRANT_MOVES = 4
        const val PRIME = 31L
        const val SWEEP_STRIDE = 7_919L
    }
}
