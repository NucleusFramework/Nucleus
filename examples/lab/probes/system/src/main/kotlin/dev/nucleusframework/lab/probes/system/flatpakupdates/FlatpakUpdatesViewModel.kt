package dev.nucleusframework.lab.probes.system.flatpakupdates

import androidx.lifecycle.ViewModel
import dev.nucleusframework.flatpak.FlatpakException
import dev.nucleusframework.flatpak.FlatpakUpdateState
import dev.nucleusframework.flatpak.FlatpakUpdateStatus
import dev.nucleusframework.lab.core.commands.LabCommands
import dev.nucleusframework.lab.core.format.summary
import dev.nucleusframework.lab.core.mvi.CallOutcome
import dev.nucleusframework.lab.core.mvi.MviViewModel
import dev.nucleusframework.lab.core.timeline.Severity
import dev.nucleusframework.lab.core.timeline.Timeline
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.coroutines.Job

@ViewModelKey
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
@Inject
class FlatpakUpdatesViewModel(
    private val gateway: FlatpakUpdatesGateway,
    commands: LabCommands,
    timeline: Timeline,
) : MviViewModel<FlatpakUpdatesState, FlatpakUpdatesIntent, FlatpakUpdatesEvent, Nothing>(
        FlatpakUpdatesState(),
        FlatpakUpdatesReducer,
        timeline,
        FlatpakUpdatesProbe.ID,
    ) {
    private var watch: Job? = null

    // Set by deep-link flags: the whole update without a click, for end-to-end runs.
    @Volatile private var autoInstall = false

    @Volatile private var autoRestart = false

    init {
        launch {
            dispatch(FlatpakUpdatesEvent.Ready(gateway.supported(), gateway.appId()))
            // nucleus-lab://probe/system.flatpak-updates?watch&install&restart
            onParams(commands) { params ->
                autoInstall = params.flag("install")
                autoRestart = params.flag("restart")
                if (params.flag("watch") && watch == null) onIntent(FlatpakUpdatesIntent.ToggleWatch)
            }
        }
    }

    override suspend fun handle(intent: FlatpakUpdatesIntent) {
        if (!state.value.supported.isAvailable) return
        when (intent) {
            FlatpakUpdatesIntent.ToggleWatch -> toggleWatch()
            is FlatpakUpdatesIntent.Install -> install(intent)
            FlatpakUpdatesIntent.Restart -> call("restart()") { gateway.restart() }
            is FlatpakUpdatesIntent.OpenStorePage -> call("openStorePage()") { gateway.openStorePage(intent.window) }
        }
    }

    private fun toggleWatch() {
        watch?.let {
            it.cancel()
            watch = null
            dispatch(FlatpakUpdatesEvent.Watching(false))
            return
        }
        dispatch(FlatpakUpdatesEvent.Watching(true))
        watch =
            launch {
                try {
                    call("status") {
                        gateway.status().collect { status ->
                            dispatch(FlatpakUpdatesEvent.Reported(status))
                            when {
                                status is FlatpakUpdateStatus.Available && autoInstall ->
                                    onIntent(FlatpakUpdatesIntent.Install(window = null))
                                status is FlatpakUpdateStatus.Installed && autoRestart ->
                                    onIntent(FlatpakUpdatesIntent.Restart)
                            }
                        }
                    }
                } finally {
                    reduceSilently(FlatpakUpdatesEvent.Watching(false))
                }
            }
    }

    private suspend fun install(intent: FlatpakUpdatesIntent.Install) {
        if (state.value.installing) return
        dispatch(FlatpakUpdatesEvent.InstallStarted)
        try {
            call("install()") {
                var last: String? = null
                gateway.install(intent.window).collect { progress ->
                    // Every report updates the readout; only state changes reach the timeline.
                    val describedState = progress.state.name
                    if (describedState != last) {
                        dispatch(FlatpakUpdatesEvent.Progressed(progress))
                    } else {
                        reduceSilently(FlatpakUpdatesEvent.Progressed(progress))
                    }
                    last = describedState
                }
                last
            }
        } finally {
            dispatch(FlatpakUpdatesEvent.InstallEnded)
        }
        if (autoRestart && state.value.progress?.state == FlatpakUpdateState.Completed) {
            onIntent(FlatpakUpdatesIntent.Restart)
        }
    }

    /** Runs [block] and records it; `null` when it threw. A linkage error is recorded too. */
    private suspend fun <T> call(
        name: String,
        block: suspend () -> T,
    ): T? =
        try {
            block().also { dispatch(FlatpakUpdatesEvent.Called(name, CallOutcome.Ok, it?.toString())) }
        } catch (e: FlatpakException) {
            dispatch(FlatpakUpdatesEvent.Called(name, CallOutcome(false, "${e.code}: ${e.message}")), Severity.Warning)
            null
        } catch (e: LinkageError) {
            dispatch(FlatpakUpdatesEvent.Called(name, CallOutcome(false, e.summary)), Severity.Error)
            null
        }
}
