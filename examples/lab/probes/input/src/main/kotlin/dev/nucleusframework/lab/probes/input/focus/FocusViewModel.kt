package dev.nucleusframework.lab.probes.input.focus

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
class FocusViewModel(
    host: SessionHost,
    timeline: Timeline,
) : MviViewModel<FocusState, FocusIntent, FocusEvent, Nothing>(FocusState(), FocusReducer, timeline, FocusProbe.ID) {
    private val windows = sessions(host)

    init {
        // However it closes (its close button, Close child, the shell's Reset), the child is gone.
        windows.onEnded(CHILD) { dispatch(FocusEvent.ChildOpened(false)) }
    }

    fun onFocusGained(target: String) = dispatch(FocusEvent.Gained(target))

    fun onFocusLost(target: String) = dispatch(FocusEvent.Lost(target))

    fun onWindowFocus(focused: Boolean) {
        if (state.value.windowFocused != focused) dispatch(FocusEvent.WindowFocus(focused))
    }

    /** Input that reached the child window; a disabled child must never report any. */
    fun onChildInput(what: String) {
        val disabled = !state.value.child.enabled
        dispatch(FocusEvent.ChildInput(what), if (disabled) Severity.Error else Severity.Info)
    }

    override suspend fun handle(intent: FocusIntent) {
        when (intent) {
            is FocusIntent.SetChild -> dispatch(FocusEvent.ChildConfigured(intent.config))
            FocusIntent.OpenChild -> {
                val config = state.value.child
                windows.open(
                    "Child window (enabled=${config.enabled}, focusable=${config.focusable})",
                    CHILD,
                ) { close ->
                    ChildWindow(config, onInput = ::onChildInput, onClose = close)
                }
                dispatch(FocusEvent.ChildOpened(true))
            }
            FocusIntent.CloseChild -> windows.close(CHILD)
            FocusIntent.Reset -> dispatch(FocusEvent.Cleared)
        }
    }

    private companion object {
        const val CHILD = "child"
    }
}
