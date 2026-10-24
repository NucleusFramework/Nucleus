package dev.nucleusframework.lab.probes.window.theming

import androidx.lifecycle.ViewModel
import dev.nucleusframework.lab.core.mvi.MviViewModel
import dev.nucleusframework.lab.core.session.SessionHost
import dev.nucleusframework.lab.core.timeline.Severity
import dev.nucleusframework.lab.core.timeline.Timeline
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey

@ViewModelKey
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
@Inject
class ThemingViewModel(
    host: SessionHost,
    timeline: Timeline,
) : MviViewModel<ThemingState, ThemingIntent, ThemingEvent, Nothing>(
        ThemingState(),
        ThemingReducer,
        timeline,
        ThemingProbe.ID,
    ) {
    private val sessions = sessions(host)

    init {
        DesignSystem.entries.forEach { system ->
            launch { sessions.isOpen(system.sessionName).collect { dispatch(ThemingEvent.OpenChanged(system, it)) } }
        }
    }

    override suspend fun handle(intent: ThemingIntent) {
        when (intent) {
            is ThemingIntent.Open ->
                sessions.open(intent.system.label, intent.system.sessionName) { close ->
                    ThemedWindow(this@ThemingViewModel, intent.system, close)
                }
            is ThemingIntent.Close -> sessions.close(intent.system.sessionName)
            is ThemingIntent.Configure -> dispatch(ThemingEvent.Configured(intent.system, intent.config))
            is ThemingIntent.Reported -> {
                if (state.value.reports[intent.system] == intent.report) return
                val mismatch = intent.report.matches(state.value.config(intent.system).theme) == false
                dispatch(
                    ThemingEvent.Reported(intent.system, intent.report),
                    if (mismatch) Severity.Warning else Severity.Info,
                )
            }
        }
    }
}

private val DesignSystem.sessionName: String get() = name.lowercase()
