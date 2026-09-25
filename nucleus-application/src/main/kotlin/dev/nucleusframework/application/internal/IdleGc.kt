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
import java.util.Properties
import java.util.logging.Logger

/**
 * Runtime side of the `nucleusOptimization { idleGc }` knob.
 * Keep the property name in sync with the plugin's `NUCLEUS_IDLE_GC_PROPERTY`, and the
 * resource key with `NUCLEUS_IDLE_GC_RESOURCE_KEY`.
 *
 * The system property (set in the jpackage `.cfg`) wins; the plugin also bakes the knob into
 * `nucleus/nucleus-app.properties`, which is the only carrier in a GraalVM native image.
 */
internal object NucleusOptimization {
    const val PROPERTY: String = "nucleus.optimization.idleGc"
    private const val RESOURCE_PATH = "nucleus/nucleus-app.properties"
    private const val RESOURCE_KEY = "optimization.idleGc"

    val isEnabled: Boolean by lazy {
        val property = System.getProperty(PROPERTY)
        if (property != null) property == "true" else readResourceFlag()
    }

    @Suppress("TooGenericExceptionCaught")
    private fun readResourceFlag(): Boolean =
        try {
            NucleusOptimization::class.java.classLoader
                ?.getResourceAsStream(RESOURCE_PATH)
                ?.use { Properties().apply { load(it) } }
                ?.getProperty(RESOURCE_KEY) == "true"
        } catch (_: Exception) {
            false
        }
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
