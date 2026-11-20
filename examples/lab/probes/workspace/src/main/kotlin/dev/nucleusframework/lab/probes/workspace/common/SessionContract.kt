package dev.nucleusframework.lab.probes.workspace.common

import androidx.compose.runtime.Immutable
import dev.nucleusframework.lab.core.mvi.Reducer

/**
 * What one open session holds (workspaces, documents) and composes from. [live] is
 * snapshot state: the probe writes it, and in-window chrome may write it back.
 */
interface SessionModel<L> {
    var live: L
}

/**
 * Every workspace probe's state: the session lifecycle and live knobs, plus the probe's
 * own [data] (declaration options, observations, saved layouts).
 */
@Immutable
data class SessionState<L, X>(
    val open: Boolean = false,
    val live: L,
    val data: X,
    /** The last action that could not run, and why. */
    val notice: String? = null,
)

/** Probe data whose declaration-time [options] only apply when the session is (re)opened. */
interface Declared<O> {
    val options: O

    /** Options the open session was built with; `null` while closed. */
    val appliedOptions: O?
}

val SessionState<*, out Declared<*>>.optionsPending: Boolean
    get() = open && data.appliedOptions != data.options

/** Intents every session probe shares; [P] wraps the probe's own. */
sealed interface SessionIntent<out L, out P> {
    /** Opens the session, or rebuilds it when open (a reset). */
    data object Open : SessionIntent<Nothing, Nothing>

    data object Close : SessionIntent<Nothing, Nothing>

    data class SetLive<L>(
        val live: L,
    ) : SessionIntent<L, Nothing>

    data class Probe<P>(
        val intent: P,
    ) : SessionIntent<Nothing, P> {
        override fun toString(): String = intent.toString()
    }
}

/** Events every session probe shares; [P] wraps the probe's own. */
sealed interface SessionEvent<out L, out P> {
    data object Opened : SessionEvent<Nothing, Nothing>

    /** The session is gone, whoever closed it (the probe, or the user closing its last window). */
    data object Ended : SessionEvent<Nothing, Nothing>

    data class LiveChanged<L>(
        val live: L,
    ) : SessionEvent<L, Nothing>

    data class Refused(
        val reason: String,
    ) : SessionEvent<Nothing, Nothing>

    data class Probe<P>(
        val event: P,
    ) : SessionEvent<Nothing, P> {
        override fun toString(): String = event.toString()
    }
}

/** Folds the shared lifecycle; the probe reduces its own events and resets its [SessionState.data]. */
abstract class SessionReducer<L, X, P> : Reducer<SessionState<L, X>, SessionEvent<L, P>> {
    final override fun reduce(
        state: SessionState<L, X>,
        event: SessionEvent<L, P>,
    ): SessionState<L, X> =
        when (event) {
            SessionEvent.Opened -> state.copy(open = true, notice = null, data = opened(state.data))
            SessionEvent.Ended -> state.copy(open = false, data = ended(state.data))
            is SessionEvent.LiveChanged -> state.copy(live = event.live)
            is SessionEvent.Refused -> state.copy(notice = event.reason)
            is SessionEvent.Probe -> reduceProbe(state, event.event)
        }

    protected abstract fun opened(data: X): X

    protected abstract fun ended(data: X): X

    protected abstract fun reduceProbe(
        state: SessionState<L, X>,
        event: P,
    ): SessionState<L, X>
}
