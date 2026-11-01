package dev.nucleusframework.lab.probes.lifecycle.watchdog

import dev.nucleusframework.core.runtime.Platform
import dev.nucleusframework.lab.core.mvi.Stamped
import dev.nucleusframework.lab.core.mvi.stampedCallbackFlow
import dev.nucleusframework.window.tao.TaoApplication
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.flow.Flow
import java.lang.management.ManagementFactory

enum class WatchdogSignal { Unresponsive, Responsive }

/** A signal with the time the watchdog fired it: the UI thread may only see it much later. */
data class TimedSignal(
    val signal: WatchdogSignal,
    val epochMillis: Long,
)

/** How the watchdog is configured for this process, as `TaoEventLoopWatchdog` reads it. */
data class WatchdogSettings(
    val detectsOnThisOs: Boolean,
    val switch: String?,
    val graceMs: String?,
    val dialog: String?,
    val debuggerAttached: Boolean,
) {
    /** Off under a debugger unless forced on, and off when switched off. */
    val effectivelyOn: Boolean
        get() = detectsOnThisOs && switch != "false" && (!debuggerAttached || switch == "true")
}

/** Port over the Tao event-loop watchdog (#643). */
interface WatchdogGateway {
    fun settings(): WatchdogSettings

    /** Registers the app's single unresponsive/responsive handler pair; closing restores none. */
    fun signals(): Flow<Stamped<TimedSignal>>

    /** Blocks the calling thread — call it on the UI thread to stall the event loop. */
    fun block(
        millis: Long,
        declaredExpected: Boolean,
    )
}

@ContributesBinding(AppScope::class)
@Inject
class TaoWatchdogGateway : WatchdogGateway {
    override fun settings(): WatchdogSettings =
        WatchdogSettings(
            detectsOnThisOs = Platform.Current == Platform.Windows,
            switch = System.getProperty("nucleus.tao.watchdog"),
            graceMs = System.getProperty("nucleus.tao.watchdogGraceMs"),
            dialog = System.getProperty("nucleus.tao.watchdogDialog"),
            debuggerAttached = ManagementFactory.getRuntimeMXBean().inputArguments.any { it.contains("jdwp") },
        )

    override fun signals(): Flow<Stamped<TimedSignal>> =
        stampedCallbackFlow {
            TaoApplication.onUnresponsive { emit(TimedSignal(WatchdogSignal.Unresponsive, System.currentTimeMillis())) }
            TaoApplication.onResponsive { emit(TimedSignal(WatchdogSignal.Responsive, System.currentTimeMillis())) }
            onClose {
                TaoApplication.onUnresponsive {}
                TaoApplication.onResponsive {}
            }
        }

    override fun block(
        millis: Long,
        declaredExpected: Boolean,
    ) {
        if (declaredExpected) {
            TaoApplication.expectUnresponsive { Thread.sleep(millis) }
        } else {
            Thread.sleep(millis)
        }
    }
}
