package dev.nucleusframework.lab.probes.system.fswatcher

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.nucleusframework.lab.core.commands.LabCommands
import dev.nucleusframework.lab.core.format.summary
import dev.nucleusframework.lab.core.mvi.MviViewModel
import dev.nucleusframework.lab.core.mvi.map
import dev.nucleusframework.lab.core.timeline.Severity
import dev.nucleusframework.lab.core.timeline.Timeline
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@ViewModelKey
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
@Inject
class FsWatcherViewModel(
    private val gateway: FsWatcherGateway,
    commands: LabCommands,
    timeline: Timeline,
) : MviViewModel<FsWatcherState, FsWatcherIntent, FsWatcherEvent, Nothing>(
        FsWatcherState(),
        FsWatcherReducer,
        timeline,
        FsWatcherProbe.ID,
    ) {
    private var session: FsWatchSession? = null
    private var collectors: List<Job> = emptyList()
    private var nextAction = 1

    init {
        dispatch(FsWatcherEvent.Ready(gateway.root.toString()))
        dispatch(FsWatcherEvent.SettingsChanged(WatchSettings(), gateway.availability(WatchSettings())))
        // nucleus-lab://probe/system.fswatcher?delivery=raw&recursive=false&watch
        onParams(commands) { params ->
            val current = state.value.settings
            val settings =
                current.copy(
                    recursive = params.bool("recursive") ?: current.recursive,
                    delivery = params.enum<EventDelivery>("delivery") ?: current.delivery,
                    backend = params.enum<Backend>("backend") ?: current.backend,
                )
            if (settings != current) onIntent(FsWatcherIntent.ChangeSettings(settings))
            if (params.flag("watch")) onIntent(FsWatcherIntent.SetWatching(true))
        }
    }

    override suspend fun handle(intent: FsWatcherIntent) {
        when (intent) {
            is FsWatcherIntent.ChangeSettings -> {
                val wasWatching = session != null
                stop()
                dispatch(FsWatcherEvent.SettingsChanged(intent.settings, gateway.availability(intent.settings)))
                // A new configuration means a new native watcher: restart it so the change is live.
                if (wasWatching) start()
            }
            is FsWatcherIntent.SetWatching -> if (intent.watching) start() else stop()
            is FsWatcherIntent.Perform -> perform(intent.action)
            FsWatcherIntent.Clear -> dispatch(FsWatcherEvent.Cleared)
        }
    }

    private suspend fun start() {
        if (session != null) return
        val opened =
            runCatching { io { gateway.open(state.value.settings) } }
                .getOrElse {
                    dispatch(FsWatcherEvent.WatchFailed(it.summary), Severity.Error)
                    return
                }
        session = opened
        // UNDISPATCHED: subscribed before this returns, so an action right after Watch is not missed.
        collectors =
            listOf(
                viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
                    opened.events.collect { stamped ->
                        dispatch(stamped.map { FsWatcherEvent.Observed(it, stamped.thread, stamped.onUiThread) })
                    }
                },
                viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
                    opened.errors.collect { stamped ->
                        dispatch(stamped.map(FsWatcherEvent::WatcherError), Severity.Error)
                    }
                },
            )
        dispatch(FsWatcherEvent.WatchStarted)
    }

    private fun stop() {
        val current = session ?: return
        collectors.forEach { it.cancel() }
        collectors = emptyList()
        runCatching { current.close() }
        session = null
        dispatch(FsWatcherEvent.WatchStopped)
    }

    private suspend fun perform(action: FsAction) {
        val id = nextAction++
        // Started before the operation: latency counts the syscall plus the backend's delivery.
        dispatch(FsWatcherEvent.ActionStarted(ActionRecord(id, action, System.currentTimeMillis(), System.nanoTime())))
        runCatching { io { gateway.perform(action) } }
            .onFailure { dispatch(FsWatcherEvent.ActionFailed(id, it.summary), Severity.Error) }
    }

    override fun onCleared() {
        collectors.forEach { it.cancel() }
        runCatching { session?.close() }
        session = null
        super.onCleared()
    }
}
