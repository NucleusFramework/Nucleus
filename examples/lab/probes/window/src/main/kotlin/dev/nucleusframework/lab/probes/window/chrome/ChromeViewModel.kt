package dev.nucleusframework.lab.probes.window.chrome

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.nucleusframework.lab.core.mvi.MviViewModel
import dev.nucleusframework.lab.core.mvi.map
import dev.nucleusframework.lab.core.session.SessionHost
import dev.nucleusframework.lab.core.timeline.Timeline
import dev.nucleusframework.lab.probes.window.shared.WindowFollow
import dev.nucleusframework.lab.probes.window.shared.WindowObserver
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey

@ViewModelKey
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
@Inject
class ChromeViewModel(
    observer: WindowObserver,
    host: SessionHost,
    timeline: Timeline,
) : MviViewModel<ChromeState, ChromeIntent, ChromeEvent, Nothing>(
        ChromeState(),
        ChromeReducer,
        timeline,
        ChromeProbe.ID,
    ) {
    private val sessions = sessions(host)
    private val follow = WindowFollow(viewModelScope, observer)

    init {
        launch { sessions.isOpen().collect { dispatch(ChromeEvent.SessionChanged(it)) } }
    }

    override suspend fun handle(intent: ChromeIntent) {
        when (intent) {
            ChromeIntent.Open -> sessions.open("Chrome lab") { close -> ChromeLabWindow(this@ChromeViewModel, close) }
            ChromeIntent.Close -> sessions.close()
            is ChromeIntent.SetConfig -> dispatch(ChromeEvent.ConfigChanged(intent.config))
            is ChromeIntent.Measured ->
                if (intent.readback != state.value.readback) dispatch(ChromeEvent.Measured(intent.readback))
            ChromeIntent.ContentDragPressed -> dispatch(ChromeEvent.ContentDragPressed)
            is ChromeIntent.Attached ->
                follow.follow(
                    intent.window,
                    // The settled snapshot carries geometry; log only the state flips.
                    onSignal = { if (!it.value.isGeometry) dispatch(it.map(ChromeEvent::Signal)) },
                    onSettled = { dispatch(ChromeEvent.Snapshot(it)) },
                )
        }
    }
}
