package dev.nucleusframework.lab.core.session

import androidx.compose.runtime.Composable
import androidx.compose.runtime.snapshotFlow
import dev.nucleusframework.application.NucleusApplicationScope
import dev.nucleusframework.lab.core.ProbeId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch

/**
 * The windows one probe opens, keyed under its id (`<probe>` or `<probe>/<name>` when it
 * opens several). Obtained in a ViewModel with `sessions(host)`; closing on reset is the
 * shell's job (`SessionHost.closeOwnedBy`), so a ViewModel never closes in `onCleared`.
 */
class ProbeSessions internal constructor(
    private val host: SessionHost,
    private val owner: ProbeId,
    private val scope: CoroutineScope,
) {
    fun key(name: String? = null): String = if (name == null) "$owner" else "$owner/$name"

    /** Opens (or replaces) the session; [content] gets its close action. */
    fun open(
        title: String,
        name: String? = null,
        content: @Composable NucleusApplicationScope.(close: () -> Unit) -> Unit,
    ) {
        host.open(LabSession(key(name), title, owner, content))
    }

    fun close(name: String? = null) = host.close(key(name))

    fun isOpen(name: String? = null): Flow<Boolean> = host.openFlow(key(name))

    /** Runs [block] each time the session goes from open to closed, whoever closed it. */
    fun onEnded(
        name: String? = null,
        block: () -> Unit,
    ): Job =
        scope.launch {
            isOpen(name).drop(1).filter { !it }.collect { block() }
        }

    /** Mirrors Compose snapshot state read by [read] into the ViewModel, one call per distinct value. */
    fun <T> observe(
        read: () -> T,
        onValue: (T) -> Unit,
    ): Job = scope.launch { snapshotFlow(read).distinctUntilChanged().collect { onValue(it) } }
}
