package dev.nucleusframework.lab.core.timeline

import androidx.compose.runtime.Immutable
import dev.nucleusframework.lab.core.ProbeId
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.atomic.AtomicLong

enum class EntryKind {
    /** The user asked for something (an MVI intent). */
    Intent,

    /** Something happened: an OS callback, a result, a state change. */
    Event,

    /** A one-shot effect sent to the UI. */
    Effect,

    /** A child process started, printed or exited. */
    Process,

    /** Free-form note. */
    Log,
}

enum class Severity { Info, Warning, Error }

@Immutable
data class TimelineEntry(
    val seq: Long,
    val epochMillis: Long,
    /** `null` for entries not tied to a probe (shell, environment, fixtures). */
    val source: ProbeId?,
    val kind: EntryKind,
    val message: String,
    val thread: String,
    /** `false` flags an entry recorded off the UI thread; `null` when the UI thread is not known yet. */
    val onUiThread: Boolean?,
    val severity: Severity = Severity.Info,
)

/** App-wide, append-only log of everything every probe asked for and observed. */
interface Timeline {
    val entries: StateFlow<List<TimelineEntry>>

    fun record(
        source: ProbeId?,
        kind: EntryKind,
        message: String,
        severity: Severity = Severity.Info,
    )

    fun clear()
}

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
class RingBufferTimeline : Timeline {
    private val seq = AtomicLong()
    private val state = MutableStateFlow<List<TimelineEntry>>(emptyList())
    override val entries: StateFlow<List<TimelineEntry>> = state.asStateFlow()

    override fun record(
        source: ProbeId?,
        kind: EntryKind,
        message: String,
        severity: Severity,
    ) {
        val thread = Thread.currentThread()
        val entry =
            TimelineEntry(
                seq = seq.incrementAndGet(),
                epochMillis = System.currentTimeMillis(),
                source = source,
                kind = kind,
                message = message,
                thread = thread.name,
                onUiThread = UiThread.isCurrent(thread),
                severity = severity,
            )
        state.update { (it + entry).takeLast(CAPACITY) }
    }

    override fun clear() {
        state.value = emptyList()
    }

    private companion object {
        const val CAPACITY = 2_000
    }
}
