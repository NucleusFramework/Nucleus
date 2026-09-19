package dev.nucleusframework.window.tao.headful

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread
import kotlin.math.max

// What every monkey case shares: the seed, the journal that makes a random
// failure readable, and the watchdog that measures `Dispatchers.Main` from
// a thread that is not on it.
//
// A monkey does not assert that "the right thing happened" — for a random
// sequence there is none. It asserts that nothing wedges and nothing is left
// behind, and it has to be *diagnosable* when it fails: the seed replays the
// action sequence, the journal names the last actions, and the watchdog dumps
// every stack the moment the loop stops answering — which the driver cannot
// do for itself, because it runs on the very dispatcher that is stuck.

/** System property that replays a red run's action sequence. */
internal const val MONKEY_SEED_PROPERTY = "nucleus.tao.headful.monkeySeed"

/** Fixed so a green run stays green; override the property to explore. */
internal const val MONKEY_DEFAULT_SEED = 20_260_903L

/** The seed of a monkey run; overridable so a red run replays exactly. */
internal fun monkeySeed(): Long = System.getProperty(MONKEY_SEED_PROPERTY)?.toLongOrNull() ?: MONKEY_DEFAULT_SEED

/**
 * System property that replaces the random walk with a fixed action list —
 * the journal of a red run pasted back, comma-separated, to turn a sequence
 * into a repro and then bisect it by deleting entries.
 */
internal const val MONKEY_SCRIPT_PROPERTY = "nucleus.tao.headful.monkeyScript"

/** The scripted actions, by enum name, or null for a random walk. */
internal fun monkeyScript(): List<String>? =
    System
        .getProperty(MONKEY_SCRIPT_PROPERTY)
        ?.split(',')
        ?.map { it.trim() }
        ?.filter { it.isNotEmpty() }
        ?.takeIf { it.isNotEmpty() }

/**
 * The last [depth] actions of a run plus what it reached, newest last.
 *
 * Concurrent because [MainLoopWatchdog] prints it from its own thread,
 * precisely when the main thread is not answering. [reached] counts what the
 * run actually did: a monkey whose every guard refuses early still passes
 * every invariant, so a green run has to say what it exercised.
 */
internal class MonkeyJournal(
    private val tag: String,
    val seed: Long,
    private val depth: Int = JOURNAL_DEPTH,
) {
    private val entries = ConcurrentLinkedDeque<String>()
    private val reached = mutableMapOf<String, Int>()

    /** Index of the action being applied, for reports. */
    @Volatile
    var step: Int = 0

    /**
     * Also echoed to stderr as it happens: a native abort (a Rust panic, a
     * SIGSEGV in a bridge) leaves no Kotlin frame to print the journal from,
     * and the last echoed line is then the only record of what was running.
     */
    fun record(action: Any) {
        if (entries.size >= depth) entries.pollFirst()
        entries.addLast("$step $action")
        System.err.println("[$tag] $step $action")
    }

    fun reach(what: String) {
        reached[what] = (reached[what] ?: 0) + 1
    }

    fun reachedCount(what: String): Int = reached[what] ?: 0

    fun reachedSummary(): String = reached.toSortedMap().toString()

    /** Only the journal, the seed and the step: safe to read from another thread. */
    fun report(): String =
        buildString {
            appendLine("  $tag seed $seed, at step $step, last ${entries.size} actions:")
            for (entry in entries) appendLine("    $entry")
        }

    fun failure(
        reason: String,
        state: String,
    ): String =
        buildString {
            appendLine("$tag failed at step $step: $reason")
            appendLine("  seed: $seed (replay with -D$MONKEY_SEED_PROPERTY=$seed)")
            appendLine("  state: $state")
            append(report())
        }

    private companion object {
        const val JOURNAL_DEPTH = 40
    }
}

/**
 * Runs [block] under the short per-action budget. An action is a handful of
 * calls and a settle, so [budgetMillis] is orders of magnitude of slack —
 * anything that exceeds it is stuck, not slow, and saying *which* action
 * wedged is worth far more than the case's own deadline firing later.
 */
internal suspend fun <T> monkeyAction(
    describe: () -> String,
    budgetMillis: Long = MONKEY_ACTION_BUDGET_MILLIS,
    block: suspend () -> T,
): T =
    try {
        withTimeout(budgetMillis) { block() }
    } catch (timeout: TimeoutCancellationException) {
        throw IllegalStateException("${describe()} never returned (budget ${budgetMillis}ms)", timeout)
    }

internal const val MONKEY_ACTION_BUDGET_MILLIS = 5_000L

/**
 * Measures `Dispatchers.Main` from a thread that is not on it.
 *
 * Every mutation, every frame and the driver itself run on the Tao event-loop
 * thread, which is also the main dispatcher. That makes the one failure a
 * monkey is hunting invisible from the inside: if the loop and the dispatcher
 * ever wait on each other, the driver is not running either, so it cannot fail
 * its own case — the suite would just hit its deadline with no clue why.
 *
 * So the heartbeat is posted from outside. A round trip unanswered for
 * [MONKEY_STALL_DUMP_MILLIS] dumps every thread's stack next to the journal,
 * which names both halves of the deadlock; one that comes back late is
 * reported as the worst stall and fails the case at the end. If it never comes
 * back the suite's own watchdog halts the process — with the dump already on
 * stderr.
 */
internal class MainLoopWatchdog(
    private val name: String,
    private val journal: () -> String,
) {
    private val worst = AtomicLong(0)
    private val stopped = AtomicBoolean(false)
    private val dumped = AtomicBoolean(false)
    private val main = CoroutineScope(Dispatchers.Main)
    private var watcher: Thread? = null

    fun start(): MainLoopWatchdog {
        watcher = thread(isDaemon = true, name = "$name-watchdog") { watch() }
        return this
    }

    /** Stops watching and answers the worst round trip it measured, in ms. */
    fun stop(): Long {
        stopped.set(true)
        watcher?.interrupt()
        main.cancel()
        return worst.get()
    }

    private fun watch() {
        try {
            while (!stopped.get()) {
                val posted = System.nanoTime()
                val beat = CountDownLatch(1)
                main.launch { beat.countDown() }
                if (!beat.await(MONKEY_STALL_DUMP_MILLIS, TimeUnit.MILLISECONDS)) {
                    dumpEveryThread()
                    // Gone for good: the suite watchdog owns the process from
                    // here, and the dump above is what it will be diagnosed on.
                    if (!beat.await(STALL_GIVE_UP_MILLIS, TimeUnit.MILLISECONDS)) return
                }
                val roundTrip = (System.nanoTime() - posted) / NANOS_PER_MILLI
                worst.accumulateAndGet(roundTrip) { a, b -> max(a, b) }
                Thread.sleep(BEAT_INTERVAL_MILLIS)
            }
        } catch (_: InterruptedException) {
            // stop() interrupted the wait; nothing left to measure.
        }
    }

    private fun dumpEveryThread() {
        if (!dumped.compareAndSet(false, true)) return
        val dump =
            buildString {
                appendLine(
                    "[$name] Dispatchers.Main has not answered in ${MONKEY_STALL_DUMP_MILLIS}ms — " +
                        "the Tao loop and the dispatcher may be deadlocked",
                )
                append(journal())
                for ((thread, frames) in Thread.getAllStackTraces()) {
                    appendLine("  \"${thread.name}\" ${thread.state}")
                    for (frame in frames) appendLine("      at $frame")
                }
            }
        System.err.println(dump)
        System.err.flush()
    }

    private companion object {
        const val BEAT_INTERVAL_MILLIS = 250L
        const val STALL_GIVE_UP_MILLIS = 30_000L
        const val NANOS_PER_MILLI = 1_000_000L
    }
}

/** A heartbeat unanswered this long is a stall worth every thread's stack. */
internal const val MONKEY_STALL_DUMP_MILLIS = 8_000L

/** Same threshold: a stall that recovered still fails the case, with the dump already printed. */
internal const val MONKEY_MAX_STALL_MILLIS = MONKEY_STALL_DUMP_MILLIS
