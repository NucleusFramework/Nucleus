package dev.nucleusframework.application.internal

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import dev.nucleusframework.application.NucleusWindow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.IdentityHashMap
import java.util.logging.Logger

/**
 * Runtime side of `nucleus.application { nucleusOptimization = true }`.
 * Keep the property name in sync with the plugin's `NUCLEUS_OPTIMIZATION_PROPERTY`.
 */
internal object NucleusOptimization {
    const val PROPERTY: String = "nucleus.optimization"

    val isEnabled: Boolean
        get() = System.getProperty(PROPERTY) == "true"
}

/**
 * Collects focus / minimized flows from every decorated window and dialog and
 * runs [System.gc] according to [IdleGcController].
 */
internal object IdleGc {
    private val logger = Logger.getLogger(IdleGc::class.java.name)
    private val controller = IdleGcController()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val jobsLock = Any()
    private val jobs = IdentityHashMap<NucleusWindow, Job>()
    private val applyLock = Any()
    private var debounceJob: Job? = null

    fun attach(window: NucleusWindow) {
        if (!NucleusOptimization.isEnabled) return
        synchronized(jobsLock) {
            if (window in jobs) return
            controller.register(window, window.focusFlow.value, window.minimizedFlow.value)
            jobs[window] =
                scope.launch {
                    launch { window.focusFlow.collect { handle(window) } }
                    launch { window.minimizedFlow.collect { handle(window) } }
                }
        }
    }

    fun detach(window: NucleusWindow) {
        val cmd =
            synchronized(jobsLock) {
                jobs.remove(window)?.cancel()
                controller.unregister(window)
            }
        apply(cmd)
    }

    private fun handle(window: NucleusWindow) {
        apply(controller.update(window, window.focusFlow.value, window.minimizedFlow.value))
    }

    private fun apply(cmd: IdleGcCommand) {
        val runNow =
            synchronized(applyLock) {
                when (cmd) {
                    IdleGcCommand.NoChange -> false
                    IdleGcCommand.Cancel -> {
                        cancelDebounce()
                        false
                    }
                    IdleGcCommand.CollectNow -> {
                        cancelDebounce()
                        true
                    }
                    IdleGcCommand.Debounce -> {
                        scheduleDebounce()
                        false
                    }
                }
            }
        if (runNow) runGc()
    }

    private fun cancelDebounce() {
        debounceJob?.cancel()
        debounceJob = null
    }

    private fun scheduleDebounce() {
        cancelDebounce()
        debounceJob =
            scope.launch {
                delay(IdleGcController.UNFOCUS_DELAY_MS)
                if (controller.shouldRunDeferredGc()) {
                    runGc()
                }
            }
    }

    private fun runGc() {
        logger.fine("Idle GC")
        @Suppress("ExplicitGarbageCollectionCall")
        System.gc()
    }
}

@Composable
internal fun ObserveIdleGc(window: NucleusWindow) {
    if (!NucleusOptimization.isEnabled) return
    DisposableEffect(window) {
        IdleGc.attach(window)
        onDispose { IdleGc.detach(window) }
    }
}
