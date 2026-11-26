package dev.nucleusframework.lab.probes.system.storeupdates

import androidx.lifecycle.ViewModel
import dev.nucleusframework.lab.core.format.summary
import dev.nucleusframework.lab.core.mvi.CallOutcome
import dev.nucleusframework.lab.core.mvi.MviViewModel
import dev.nucleusframework.lab.core.timeline.Severity
import dev.nucleusframework.lab.core.timeline.Timeline
import dev.nucleusframework.store.microsoft.StoreException
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey

@ViewModelKey
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
@Inject
class StoreUpdatesViewModel(
    private val gateway: StoreUpdatesGateway,
    timeline: Timeline,
) : MviViewModel<StoreUpdatesState, StoreUpdatesIntent, StoreUpdatesEvent, Nothing>(
        StoreUpdatesState(),
        StoreUpdatesReducer,
        timeline,
        StoreUpdatesProbe.ID,
    ) {
    init {
        dispatch(StoreUpdatesEvent.Ready(gateway.supported()))
    }

    override suspend fun handle(intent: StoreUpdatesIntent) {
        if (!state.value.supported.isAvailable) return
        when (intent) {
            StoreUpdatesIntent.Check ->
                call("check()") { gateway.check() }?.let { dispatch(StoreUpdatesEvent.Checked(it)) }
            StoreUpdatesIntent.OpenStorePage -> call("openStorePage()") { gateway.openStorePage() }
            is StoreUpdatesIntent.Install -> install(intent)
        }
    }

    private suspend fun install(intent: StoreUpdatesIntent.Install) {
        if (state.value.installing) return
        val name = if (intent.silent) "install(silent)" else "install()"
        dispatch(StoreUpdatesEvent.InstallStarted)
        try {
            call(name) {
                var last: String? = null
                gateway.install(intent.silent, intent.hwnd).collect { progress ->
                    // Every report goes to the list; only state changes reach the timeline.
                    val describedState = progress.state.name
                    if (describedState != last) {
                        dispatch(StoreUpdatesEvent.Progressed(progress))
                    } else {
                        reduceSilently(StoreUpdatesEvent.Progressed(progress))
                    }
                    last = describedState
                }
                last
            }
        } finally {
            dispatch(StoreUpdatesEvent.InstallEnded)
        }
    }

    /** Runs [block] and records it; `null` when it threw. A linkage error is recorded too. */
    private suspend fun <T> call(
        name: String,
        block: suspend () -> T,
    ): T? =
        try {
            block().also { dispatch(StoreUpdatesEvent.Called(name, CallOutcome.Ok, it?.toString())) }
        } catch (e: StoreException) {
            dispatch(StoreUpdatesEvent.Called(name, CallOutcome(false, "${e.code}: ${e.message}")), Severity.Warning)
            null
        } catch (e: LinkageError) {
            dispatch(StoreUpdatesEvent.Called(name, CallOutcome(false, e.summary)), Severity.Error)
            null
        }
}
