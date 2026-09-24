package dev.nucleusframework.window.tao.headful

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.rememberWindowState
import dev.nucleusframework.window.tao.DecoratedWindow
import dev.nucleusframework.window.tao.TaoApplication
import dev.nucleusframework.window.tao.TaoEventLoopWatchdog
import dev.nucleusframework.window.tao.taoApplication
import kotlinx.coroutines.delay
import java.util.concurrent.atomic.AtomicInteger
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger

/**
 * Black-box smoke for the #643 watchdog: shows a plain window, freezes the
 * event loop for real, then prints a one-line machine-checkable verdict
 *
 * ```
 * [watchdog-smoke] severe=1 unresponsive=1 responsive=1
 * ```
 *
 * and exits. Each configuration of the watchdog is one run of this main with
 * different flags, which is how the whole switch surface is verified from the
 * outside — see the `taoWatchdogSmoke` Gradle task:
 *
 * - default → `severe=1 unresponsive=1 responsive=1`
 * - `-Dnucleus.tao.watchdog=false` → all zero
 * - a JDWP agent on the command line → all zero (debug sessions are exempt)
 * - a JDWP agent + `-Dnucleus.tao.watchdog=true` → back to one each
 * - `-Dnucleus.tao.watchdogDialog=true` → same counts, plus the native
 *   "Application Not Responding" dialog on screen; `holdMs` keeps the process
 *   alive long enough to look at it.
 */
object WatchdogDialogSmokeMain {
    @JvmStatic
    fun main(args: Array<String>) {
        val freezeMs = longProperty("freezeMs", DEFAULT_FREEZE_MS)
        val freezeAfterMs = longProperty("freezeAfterMs", DEFAULT_SETTLE_MS)
        val drainMs = longProperty("drainMs", DEFAULT_DRAIN_MS)
        val holdMs = longProperty("holdMs", 0L)

        val severe = AtomicInteger()
        val unresponsive = AtomicInteger()
        val responsive = AtomicInteger()
        Logger.getLogger(TaoEventLoopWatchdog::class.java.name).addHandler(
            object : Handler() {
                override fun publish(record: LogRecord) {
                    if (record.level == Level.SEVERE) severe.incrementAndGet()
                }

                override fun flush() = Unit

                override fun close() = Unit
            },
        )
        TaoApplication.onUnresponsive { unresponsive.incrementAndGet() }
        TaoApplication.onResponsive { responsive.incrementAndGet() }

        taoApplication {
            DecoratedWindow(
                onCloseRequest = ::exitApplication,
                state = rememberWindowState(size = DpSize(WINDOW_W_DP.dp, WINDOW_H_DP.dp)),
                title = "tao watchdog smoke #643",
            ) {
                Box(Modifier.fillMaxSize().background(Color(BACKDROP_ARGB)))
                LaunchedEffect(Unit) {
                    delay(freezeAfterMs)
                    // Runs on Dispatchers.Main — the event-loop thread. This is
                    // what a deadlocked loop looks like from the outside.
                    println("[watchdog-smoke] freezing the event loop for $freezeMs ms")
                    Thread.sleep(freezeMs)
                    println("[watchdog-smoke] loop resumed")
                    // Let the watchdog take the sample that closes the episode.
                    delay(drainMs)
                    println(
                        "[watchdog-smoke] severe=${severe.get()} " +
                            "unresponsive=${unresponsive.get()} responsive=${responsive.get()}",
                    )
                    // A dialog run is meant to be looked at; everything else exits at once.
                    delay(holdMs)
                    exitApplication()
                }
            }
        }
    }

    private fun longProperty(
        name: String,
        default: Long,
    ): Long = System.getProperty("nucleus.tao.watchdog.smoke.$name")?.toLongOrNull() ?: default

    private const val DEFAULT_FREEZE_MS = 20_000L
    private const val DEFAULT_SETTLE_MS = 3_000L
    private const val DEFAULT_DRAIN_MS = 6_000L
    private const val WINDOW_W_DP = 480
    private const val WINDOW_H_DP = 320
    private const val BACKDROP_ARGB = 0xFF1E1F22
}
