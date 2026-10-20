package dev.nucleusframework.lab.probes.input.clipboard

import androidx.lifecycle.ViewModel
import dev.nucleusframework.lab.core.format.summary
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
class ClipboardViewModel(
    timeline: Timeline,
) : MviViewModel<ClipboardState, ClipboardIntent, ClipboardEvent, Nothing>(
        ClipboardState(),
        ClipboardReducer,
        timeline,
        ClipboardProbe.ID,
    ) {
    private var port: ClipboardPort? = null
    private var watcher: Job? = null

    /** The window's clipboard, bound while the probe is composed. */
    fun bind(port: ClipboardPort?) {
        this.port = port
        reduceSilently(ClipboardEvent.Bound(port != null))
        if (port == null) {
            watcher?.cancel()
        } else if (state.value.watching) {
            startWatching()
        }
    }

    /** Keystroke-rate: the draft is state, not a timeline event. */
    fun onDraft(text: String) = reduceSilently(ClipboardEvent.DraftChanged(text))

    override suspend fun handle(intent: ClipboardIntent) {
        when (intent) {
            is ClipboardIntent.Write -> {
                val port = port ?: return
                guarded { port.write(intent.payload, state.value.draft) }?.let {
                    dispatch(ClipboardEvent.Written(intent.payload))
                    // Read our own write back at once, so the watcher does not count it as external.
                    guarded { port.read() }?.let { dispatch(ClipboardEvent.ReadBack(it)) }
                }
            }
            ClipboardIntent.Read ->
                port?.let { p ->
                    guarded { p.read() }?.let { dispatch(ClipboardEvent.ReadBack(it)) }
                }
            is ClipboardIntent.Watch -> {
                dispatch(ClipboardEvent.WatchChanged(intent.on))
                if (intent.on) startWatching() else watcher?.cancel()
            }
        }
    }

    /** Re-reads the clipboard every second; only a change made outside the Lab reaches the timeline. */
    private fun startWatching() {
        watcher?.cancel()
        watcher =
            poll(
                POLL_MS,
                read = { port?.let { p -> guarded { p.read() } } },
                toEvent = ClipboardEvent::Polled,
                isChange = { _, read -> read != null && state.value.isExternalChange(read) },
            )
    }

    private suspend fun <T> guarded(block: suspend () -> T): T? =
        runCatching { block() }
            .onFailure { dispatch(ClipboardEvent.Failed(it.summary), Severity.Error) }
            .getOrNull()

    private companion object {
        const val POLL_MS = 1_000L
    }
}
