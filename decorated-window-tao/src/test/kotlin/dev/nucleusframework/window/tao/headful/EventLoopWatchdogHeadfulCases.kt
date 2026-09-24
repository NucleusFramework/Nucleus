package dev.nucleusframework.window.tao.headful

import dev.nucleusframework.core.runtime.Platform
import dev.nucleusframework.window.tao.TaoApplication
import dev.nucleusframework.window.tao.TaoEventLoopWatchdog
import dev.nucleusframework.window.tao.ffi.NativeTaoBridge
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger
import kotlin.concurrent.thread

/**
 * End-to-end coverage for the hang watchdog (#643), in a real app process with
 * a real window and the default configuration — no lowered thresholds.
 *
 * The case driver runs on the composition dispatcher, i.e. the event-loop
 * thread itself, so a plain [Thread.sleep] there stops the message pump for
 * real: the same observable state as #640's deadlock, which is all
 * `IsHungAppWindow` measures. Two independent things are asserted while it is
 * frozen — that the OS actually flags the window (a second thread polls the
 * native probe throughout), and that the watchdog turns that into one `SEVERE`
 * report carrying a thread dump that names the event-loop thread sitting in
 * `nativeRunBlocking`. That log line is exactly what #640 never produced.
 */
internal object EventLoopWatchdogHeadfulCases {
    fun all(): List<TaoWindowTestCase> =
        listOf(
            TaoWindowTestCase(
                "watchdog reports a frozen event loop with a thread dump (#643)",
                // The freeze alone outlasts the default case timeout.
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

                // Resolved here, while the loop still runs: the native window
                // map is behind a mutex the frozen loop can be holding.
                val hwnd = NativeTaoBridge.nativeHwndHandle(window.handle)
                check(hwnd != 0L) { "no HWND for the case window" }

                val records = CopyOnWriteArrayList<LogRecord>()
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

                // Electron parity: the app hears about the stall and its end.
                val unresponsive = AtomicInteger()
                val responsive = AtomicInteger()
                TaoApplication.onUnresponsive { unresponsive.incrementAndGet() }
                TaoApplication.onResponsive { responsive.incrementAndGet() }

                // Independent witness: the OS's own verdict, sampled from a
                // thread the freeze does not touch.
                val osFlaggedHung = AtomicBoolean(false)
                val stop = AtomicBoolean(false)
                val observer =
                    thread(isDaemon = true, name = "watchdog-case-observer") {
                        try {
                            while (!stop.get()) {
                                if (NativeTaoBridge.nativeIsWindowHung(hwnd)) osFlaggedHung.set(true)
                                Thread.sleep(OBSERVE_INTERVAL_MS)
                            }
                        } catch (_: InterruptedException) {
                            Thread.currentThread().interrupt() // stopped by the case
                        }
                    }

                try {
                    // The freeze. Blocking, on the event-loop thread, on
                    // purpose — the window really stops responding and Windows
                    // really ghosts it.
                    Thread.sleep(FREEZE_MS)

                    // Back on our feet: give the watchdog a sample to see it.
                    awaitUntil(
                        "watchdog reported the recovery",
                        timeoutMillis = RECOVERY_TIMEOUT_MS,
                        detail = { records.joinToString { "${it.level}: ${it.message.lineSequence().first()}" } },
                    ) {
                        records.any { it.level == Level.INFO && "responded again" in it.message }
                    }
                } finally {
                    stop.set(true)
                    observer.interrupt()
                    logger.removeHandler(collector)
                }

                check(osFlaggedHung.get()) {
                    "IsHungAppWindow never flagged the window during a ${FREEZE_MS}ms freeze — " +
                        "the probe, not the watchdog, is what failed"
                }

                val stalls = records.filter { it.level == Level.SEVERE }
                check(stalls.size == 1) {
                    "expected exactly one SEVERE stall report, got ${stalls.size}: " +
                        stalls.joinToString { it.message.lineSequence().first() }
                }
                val report = stalls.single().message
                check("has not pumped messages" in report) { "unexpected report: ${report.lineSequence().first()}" }
                check("(Tao event loop)" in report) { "the report does not mark the event-loop thread:\n$report" }
                check("nativeRunBlocking" in report) {
                    "the dump does not show the loop thread inside nativeRunBlocking:\n$report"
                }
                check(unresponsive.get() == 1) {
                    "onUnresponsive fired ${unresponsive.get()} times, expected once"
                }
                check(responsive.get() == 1) {
                    "onResponsive fired ${responsive.get()} times, expected once"
                }
            },
        )

    /** Well past Windows' ~5 s hung threshold plus the watchdog's default grace. */
    private const val FREEZE_MS = 20_000L
    private const val OBSERVE_INTERVAL_MS = 500L
    private const val RECOVERY_TIMEOUT_MS = 15_000L
    private const val CASE_TIMEOUT_MS = 90_000L
}
