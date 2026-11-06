package dev.nucleusframework.lab.probes.input.keyboard

import androidx.lifecycle.ViewModel
import dev.nucleusframework.lab.core.mvi.MviViewModel
import dev.nucleusframework.lab.core.timeline.EntryKind
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
class KeyboardViewModel(
    timeline: Timeline,
) : MviViewModel<KeyboardState, KeyboardIntent, KeyboardEvent, Nothing>(
        KeyboardState(),
        KeyboardReducer,
        timeline,
        KeyboardProbe.ID,
    ) {
    /** Key events: repeats and plain keys stay in the state, an unmatched KeyUp is an anomaly worth a timeline line. */
    fun onKey(record: KeyRecord) {
        val event = KeyboardEvent.Key(record)
        if (!record.down && record.nativeCode !in state.value.held) {
            dispatch(event, Severity.Warning)
        } else {
            reduceSilently(event)
        }
    }

    /** Every text field change; IME commits are logged, keystroke-level edits are not. */
    fun onText(snapshot: TextSnapshot) {
        val previousHead = state.value.commits.firstOrNull()
        reduceSilently(KeyboardEvent.Text(snapshot))
        // A new commit is a new head entry (each one is a fresh string, so identity tells).
        val head = state.value.commits.firstOrNull()
        if (head != null && head !== previousHead) timeline.record(source, EntryKind.Event, "IME $head")
    }

    override suspend fun handle(intent: KeyboardIntent) {
        when (intent) {
            KeyboardIntent.Reset -> dispatch(KeyboardEvent.Cleared)
        }
    }

    override fun describe(value: Any): String =
        if (value is KeyboardEvent.Key) {
            with(value.record) { "${if (down) "KeyDown" else "KeyUp"} $key code=$nativeCode (no matching KeyDown)" }
        } else {
            super.describe(value)
        }
}
