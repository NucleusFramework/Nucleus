package dev.nucleusframework.lab.core.session

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import dev.nucleusframework.application.NucleusApplicationScope
import dev.nucleusframework.lab.core.ProbeId
import dev.nucleusframework.lab.core.timeline.EntryKind
import dev.nucleusframework.lab.core.timeline.Timeline
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/**
 * Windows a probe opens beside the shell — a tab workspace, a watermark overlay, a desktop
 * widget. They are composed at the application root, so they outlive navigation: switching
 * probe does not close the workspace under test.
 */
@Immutable
class LabSession(
    /** Opening a session with a key already open replaces it (a reset). */
    val key: String,
    val title: String,
    val owner: ProbeId,
    val content: @Composable NucleusApplicationScope.(close: () -> Unit) -> Unit,
)

@SingleIn(AppScope::class)
@Inject
class SessionHost(
    private val timeline: Timeline,
) {
    private val state = MutableStateFlow<List<LabSession>>(emptyList())
    val sessions: StateFlow<List<LabSession>> = state.asStateFlow()

    fun open(session: LabSession) {
        timeline.record(session.owner, EntryKind.Event, "Session opened: ${session.title}")
        state.update { list -> list.filterNot { it.key == session.key } + session }
    }

    fun close(key: String) {
        val closing = state.value.firstOrNull { it.key == key } ?: return
        timeline.record(closing.owner, EntryKind.Event, "Session closed: ${closing.title}")
        state.update { list -> list.filterNot { it.key == key } }
    }

    fun isOpen(key: String): Boolean = state.value.any { it.key == key }

    /** Whether the session [key] is open, as it changes: a probe mirrors it into its state. */
    fun openFlow(key: String): Flow<Boolean> = state.map { list -> list.any { it.key == key } }.distinctUntilChanged()

    /** Closes every session [owner] opened: resetting a probe must not leave its windows on the old ViewModel. */
    fun closeOwnedBy(owner: ProbeId) {
        state.value.filter { it.owner == owner }.forEach { close(it.key) }
    }
}
