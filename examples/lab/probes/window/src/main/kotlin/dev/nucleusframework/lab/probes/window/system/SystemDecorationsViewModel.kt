package dev.nucleusframework.lab.probes.window.system

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogState
import androidx.compose.ui.window.WindowState
import androidx.lifecycle.ViewModel
import dev.nucleusframework.lab.core.mvi.MviViewModel
import dev.nucleusframework.lab.core.session.SessionHost
import dev.nucleusframework.lab.core.timeline.Timeline
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey

@ViewModelKey
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
@Inject
class SystemDecorationsViewModel(
    host: SessionHost,
    timeline: Timeline,
) : MviViewModel<SystemDecorationsState, SystemDecorationsIntent, SystemDecorationsEvent, Nothing>(
        SystemDecorationsState(),
        SystemDecorationsReducer,
        timeline,
        SystemDecorationsProbe.ID,
    ) {
    val windowState = WindowState(size = DpSize(560.dp, 380.dp))
    val dialogState = DialogState(size = DpSize(420.dp, 220.dp))

    private val sessions = sessions(host)

    init {
        SystemSurface.entries.forEach { surface ->
            launch {
                sessions.isOpen(surface.sessionName).collect {
                    dispatch(SystemDecorationsEvent.OpenChanged(surface, it))
                }
            }
        }
    }

    override suspend fun handle(intent: SystemDecorationsIntent) {
        when (intent) {
            is SystemDecorationsIntent.Open ->
                sessions.open(intent.surface.name, intent.surface.sessionName) { close ->
                    when (intent.surface) {
                        SystemSurface.Window -> SystemDecoratedWindow(this@SystemDecorationsViewModel, close)
                        SystemSurface.Dialog -> SystemDecoratedDialog(this@SystemDecorationsViewModel, close)
                    }
                }
            is SystemDecorationsIntent.Close -> sessions.close(intent.surface.sessionName)
            is SystemDecorationsIntent.SetWithTitleBar ->
                dispatch(SystemDecorationsEvent.WithTitleBarChanged(intent.enabled))
            is SystemDecorationsIntent.TargetPressed ->
                dispatch(SystemDecorationsEvent.TargetPressed("%.0f, %.0f dp".format(intent.x, intent.y)))
        }
    }
}

private val SystemSurface.sessionName: String get() = name.lowercase()
