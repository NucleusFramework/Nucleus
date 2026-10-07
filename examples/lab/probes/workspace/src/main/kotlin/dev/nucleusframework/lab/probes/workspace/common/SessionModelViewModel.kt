package dev.nucleusframework.lab.probes.workspace.common

import androidx.compose.runtime.Composable
import dev.nucleusframework.application.NucleusApplicationScope
import dev.nucleusframework.lab.core.ProbeId
import dev.nucleusframework.lab.core.mvi.MviViewModel
import dev.nucleusframework.lab.core.session.SessionHost
import dev.nucleusframework.lab.core.timeline.Timeline
import kotlinx.coroutines.Job

/**
 * A probe whose subject is a set of windows opened as a Lab session (core `ProbeSessions`):
 * on top of it, the [model] the open session composes from, its live knobs mirrored both
 * ways, and snapshot observers that live exactly as long as that session.
 */
abstract class SessionModelViewModel<L : Any, X : Any, I : Any, E : Any, M : SessionModel<L>>(
    initialState: SessionState<L, X>,
    reducer: SessionReducer<L, X, E>,
    host: SessionHost,
    timeline: Timeline,
    source: ProbeId,
) : MviViewModel<SessionState<L, X>, SessionIntent<L, I>, SessionEvent<L, E>, Nothing>(
        initialState,
        reducer,
        timeline,
        source,
    ) {
    private val sessions = sessions(host)
    private var observers: List<Job> = emptyList()

    /** The open session's model; `null` while closed. */
    protected var model: M? = null
        private set

    protected abstract val sessionTitle: String

    /** Builds the model a fresh session composes from, with the current state's options. */
    protected abstract fun createModel(state: SessionState<L, X>): M

    protected abstract fun content(model: M): @Composable NucleusApplicationScope.(close: () -> Unit) -> Unit

    /** Starts the probe's observers of the session just opened (see [mirror]). */
    protected abstract fun onOpened(model: M)

    protected abstract suspend fun handleProbe(intent: I)

    init {
        sessions.onEnded {
            stopObserving()
            model = null
            dispatch(SessionEvent.Ended)
        }
    }

    /** UI shortcut for a probe intent. */
    fun act(intent: I) = onIntent(SessionIntent.Probe(intent))

    final override suspend fun handle(intent: SessionIntent<L, I>) {
        when (intent) {
            SessionIntent.Open -> open()
            SessionIntent.Close -> sessions.close()
            is SessionIntent.SetLive -> {
                val previous = state.value.live
                dispatch(SessionEvent.LiveChanged(intent.live))
                model?.let { applyLive(it, previous, intent.live) }
            }
            is SessionIntent.Probe -> handleProbe(intent.intent)
        }
    }

    /** Pushes new live knobs into the open session; override for knobs with side effects. */
    protected open fun applyLive(
        model: M,
        previous: L,
        live: L,
    ) {
        model.live = live
    }

    private fun open() {
        stopObserving()
        val next = createModel(state.value)
        model = next
        sessions.open(sessionTitle, content = content(next))
        dispatch(SessionEvent.Opened)
        onOpened(next)
        // In-window chrome may write the live knobs (a close button, a style toggle): mirror them back.
        mirror({ next.live }) { live -> if (live != state.value.live) dispatch(SessionEvent.LiveChanged(live)) }
    }

    /**
     * Mirrors [read] (Compose snapshot state) into the flow: [onValue] runs for every
     * distinct value until the session is replaced or ends.
     */
    protected fun <T> mirror(
        read: () -> T,
        onValue: (T) -> Unit,
    ) {
        observers = observers + sessions.observe(read, onValue)
    }

    private fun stopObserving() {
        observers.forEach { it.cancel() }
        observers = emptyList()
    }

    protected fun dispatchProbe(event: E) = dispatch(SessionEvent.Probe(event))

    /** For observations: they reach the state, not the timeline. */
    protected fun reduceProbeSilently(event: E) = reduceSilently(SessionEvent.Probe(event))

    protected fun refuse(reason: String) = dispatch(SessionEvent.Refused(reason))

    /** Runs [block] against the open session's model, or refuses while closed. */
    protected inline fun withModel(block: M.() -> Unit) {
        val current = model
        if (current == null) refuse("open the session first") else current.block()
    }
}
