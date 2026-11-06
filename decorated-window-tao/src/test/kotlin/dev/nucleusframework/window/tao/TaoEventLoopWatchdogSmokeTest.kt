package dev.nucleusframework.window.tao

import androidx.compose.runtime.LaunchedEffect
import dev.nucleusframework.core.runtime.Platform
import kotlinx.coroutines.delay
import java.util.concurrent.CopyOnWriteArrayList
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Opt-in end-to-end test (set `NUCLEUS_TAO_SMOKE=1`) for the hang watchdog
 * (#643): opens a real Tao window, then **really** stops the message pump by
 * sleeping on the event-loop thread, and asserts that the watchdog logged
 * `SEVERE` with a thread dump — the report that #640 never produced.
 *
 * Not run by default: it takes over the calling thread with the native event
 * loop, needs a display, and freezes it for ~[FREEZE_MS] on purpose. Windows
 * only, like the watchdog itself.
 */
class TaoEventLoopWatchdogSmokeTest {
    @Test
    @Suppress("SwallowedException")
    fun aFrozenEventLoopIsReportedWithAThreadDump() {
        if (System.getenv("NUCLEUS_TAO_SMOKE") == null || Platform.Current != Platform.Windows) {
            println("SKIPPED: set NUCLEUS_TAO_SMOKE=1 on Windows to run the watchdog e2e test")
            return
        }
        // The OS sets its hung flag after ~5 s without pumping; keep the extra
        // grace short so the freeze does not have to outlast the default one.
        System.setProperty("nucleus.tao.watchdogGraceMs", "1000")

        val reports = CopyOnWriteArrayList<LogRecord>()
        val logger = Logger.getLogger(TaoEventLoopWatchdog::class.java.name)
        val collector =
            object : Handler() {
                override fun publish(record: LogRecord) {
                    reports += record
                }

                override fun flush() = Unit

                override fun close() = Unit
            }
        logger.addHandler(collector)

        // Same halt-on-hang guard the other smoke tests use: the loop takes
        // over this thread, so a test that never reaches exitApplication would
        // hang the forked JVM with no timeout.
        val bailout =
            thread(isDaemon = true, name = "tao-watchdog-smoke-bailout") {
                try {
                    Thread.sleep(BAILOUT_MS)
                } catch (_: InterruptedException) {
                    return@thread
                }
                Runtime.getRuntime().halt(BAILOUT_EXIT_CODE)
            }

        try {
            taoApplication(exitProcessOnExit = false) {
                DecoratedWindow(onCloseRequest = ::exitApplication, title = "watchdog-smoke") {
                    LaunchedEffect(Unit) {
                        delay(SETTLE_MS) // let the window map and paint
                        // Runs on Dispatchers.Main — i.e. the event-loop
                        // thread, which stops pumping for real. This is the
                        // shape of #640, without needing its deadlock.
                        Thread.sleep(FREEZE_MS)
                        delay(DRAIN_MS) // let the watchdog's last sample land
                        exitApplication()
                    }
                }
            }
        } finally {
            bailout.interrupt()
            logger.removeHandler(collector)
            System.clearProperty("nucleus.tao.watchdogGraceMs")
        }

        val stall = reports.firstOrNull { it.level == Level.SEVERE }
        assertTrue(
            stall != null,
            "the watchdog logged nothing while the event loop was frozen for $FREEZE_MS ms " +
                "(records: ${reports.map { "${it.level}: ${it.message.lineSequence().first()}" }})",
        )
        assertTrue(
            "has not pumped messages" in stall.message,
            "unexpected watchdog report: ${stall.message.lineSequence().first()}",
        )
        assertTrue(
            "(Tao event loop)" in stall.message && "nativeRunBlocking" in stall.message,
            "the report must carry a thread dump naming the event-loop thread:\n${stall.message}",
        )
        // The stall is reported once, not once per poll.
        assertTrue(
            reports.count { it.level == Level.SEVERE } == 1,
            "expected exactly one SEVERE report, got ${reports.count { it.level == Level.SEVERE }}",
        )
        // And the recovery is reported when the loop pumps again.
        assertTrue(
            reports.any { it.level == Level.INFO && "responded again" in it.message },
            "the watchdog did not report the recovery",
        )
    }

    private companion object {
        const val SETTLE_MS = 3_000L

        /** Well past the OS's ~5 s hung threshold plus the grace above. */
        const val FREEZE_MS = 15_000L
        const val DRAIN_MS = 4_000L
        const val BAILOUT_MS = 120_000L
        const val BAILOUT_EXIT_CODE = 42
    }
}
