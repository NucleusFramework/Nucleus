package dev.nucleusframework.window.tao.headful

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
 * The watchdog monkey against a **real window** (#643).
 *
 * `TaoEventLoopWatchdogMonkeyTest` hammers the lifecycle with a fake probe: it
 * finds races, and it found three, but every sample it takes is a lie. This one
 * takes none: a real `DecoratedWindow`, the real Tao event loop, the real
 * `IsHungAppWindow`, and freezes made the only way a freeze can be made — by
 * blocking the thread that pumps messages, which is the thread this driver runs
 * on.
 *
 * The moves are the ones an app really makes, in a random order:
 *
 * - a **short** freeze, below Windows' own ~5 s threshold: must produce nothing,
 * - a **long** freeze: must produce exactly one report, paired with its recovery,
 * - a long freeze inside `expectUnresponsive { }`: must produce nothing,
 * - a listener that throws, and one that calls back into the watchdog,
 * - the watchdog stopped and started under the app's feet.
 *
 * What it asserts is what an app can rely on: no report without a real freeze,
 * no freeze past the threshold without a report, every `unresponsive` paired,
 * and — the part only a real window can check — the window still lives, paints
 * and reports a frame once the storm is over.
 */
internal object EventLoopWatchdogMonkeyHeadfulCases {
    @Suppress("LongMethod") // one flat case: setup, storm, invariants
    fun all(): List<TaoWindowTestCase> =
        listOf(
            TaoWindowTestCase(
                "watchdog monkey: real window, real freezes (#643)",
                timeoutMillis = CASE_TIMEOUT_MS,
                skip = {
                    if (Platform.Current != Platform.Windows) {
                        "IsHungAppWindow is Windows-only — no non-perturbing probe elsewhere yet"
                    } else {
                        null
                    }
                },
            ) {
                awaitUntil("window mapped") { window.hasRealFramePx() }
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

                fun countingHandlers() {
                    TaoApplication.onUnresponsive { unresponsive.incrementAndGet() }
                    TaoApplication.onResponsive { responsive.incrementAndGet() }
                }
                countingHandlers()

                // Shorten the grace so a storm of real freezes fits in a case:
                // the OS's own ~5 s threshold stays, which is what keeps the
                // freezes honest. Applied by restarting the watchdog, since the
                // grace is read when a run starts.
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
                        val action = MonkeyMove.entries[random.nextInt(MonkeyMove.entries.size)]
                        journal += "#$move $action"
                        val before = records.count { it.level == Level.SEVERE }
                        when (action) {
                            MonkeyMove.ShortFreeze -> Thread.sleep(random.nextLong(300, SHORT_FREEZE_MAX_MS))
                            MonkeyMove.LongFreeze -> {
                                Thread.sleep(random.nextLong(LONG_FREEZE_MIN_MS, LONG_FREEZE_MAX_MS))
                                expectedReports++
                                // Waited for here, not counted at the end: a
                                // report that lands during the *next* move would
                                // otherwise read as "a short freeze was
                                // reported". Each long freeze answers for itself.
                                awaitUntil(
                                    "the long freeze was reported",
                                    timeoutMillis = REPORT_TIMEOUT_MS,
                                ) {
                                    records.count { it.level == Level.SEVERE } > before
                                }
                            }
                            MonkeyMove.ExpectedLongFreeze ->
                                TaoApplication.expectUnresponsive {
                                    Thread.sleep(random.nextLong(LONG_FREEZE_MIN_MS, LONG_FREEZE_MAX_MS))
                                }
                            MonkeyMove.HostileListener ->
                                TaoApplication.onUnresponsive {
                                    unresponsive.incrementAndGet()
                                    error("hostile listener")
                                }
                            MonkeyMove.ReentrantListener ->
                                TaoApplication.onUnresponsive {
                                    unresponsive.incrementAndGet()
                                    TaoEventLoopWatchdog.registerWindow(window.handle)
                                }
                            MonkeyMove.CleanListener -> countingHandlers()
                            MonkeyMove.RestartWatchdog -> {
                                TaoEventLoopWatchdog.stop()
                                TaoEventLoopWatchdog.start()
                                TaoEventLoopWatchdog.registerWindow(window.handle)
                            }
                        }
                        // Let the watchdog take its samples with the loop alive:
                        // suspending keeps the pump running, which is what makes
                        // the window healthy again.
                        settle(SETTLE_MS)
                        val after = records.count { it.level == Level.SEVERE }
                        if (action == MonkeyMove.ShortFreeze && after != before) {
                            fail(journal, "a ${SHORT_FREEZE_MAX_MS}ms freeze was reported", records)
                        }
                        if (action == MonkeyMove.ExpectedLongFreeze && after != before) {
                            fail(journal, "a freeze inside expectUnresponsive was reported", records)
                        }
                    }

                    // Every unguarded long freeze must have been reported. The
                    // OS flag is the floor, not the ceiling: a report may also
                    // land one sample late, so this is a lower bound.
                    val reports = records.count { it.level == Level.SEVERE }
                    if (reports < expectedReports) {
                        fail(journal, "only $reports report(s) for $expectedReports long freeze(s)", records)
                    }

                    countingHandlers()
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
                    TaoEventLoopWatchdog.stop()
                    TaoEventLoopWatchdog.start()
                }

                // The part only a real window can answer: the storm left the app
                // alive. The loop pumps, the window paints, and the OS agrees.
                window.requestRedraw()
                awaitUntil("the window still reports a real frame") { window.hasRealFramePx() }
                val live =
                    Thread
                        .getAllStackTraces()
                        .keys
                        .count { it.isAlive && it.name == "nucleus-tao-watchdog" }
                check(live <= 1) { "the storm left $live watchdog threads alive" }
            },
        )

    private fun fail(
        journal: List<String>,
        reason: String,
        records: Collection<LogRecord>,
    ): Nothing =
        error(
            buildString {
                appendLine(reason)
                appendLine("  seed: ${monkeySeed()} (replay with -D$MONKEY_SEED_PROPERTY=${monkeySeed()})")
                appendLine("  moves:")
                journal.forEach { appendLine("    $it") }
                records
                    .filter { it.level == Level.SEVERE }
                    .forEach { appendLine("    report: ${it.message.lineSequence().first()}") }
            },
        )

    private enum class MonkeyMove {
        ShortFreeze,
        LongFreeze,
        ExpectedLongFreeze,
        HostileListener,
        ReentrantListener,
        CleanListener,
        RestartWatchdog,
    }

    private const val GRACE_PROPERTY = "nucleus.tao.watchdogGraceMs"
    private const val MOVES = 12
    private const val SHORT_FREEZE_MAX_MS = 2_500L
    private const val LONG_FREEZE_MIN_MS = 8_000L
    private const val LONG_FREEZE_MAX_MS = 11_000L
    private const val SETTLE_MS = 3_000L
    private const val PAIRING_TIMEOUT_MS = 20_000L
    private const val REPORT_TIMEOUT_MS = 20_000L
    private const val CASE_TIMEOUT_MS = 300_000L
}
