package dev.nucleusframework.lab.core.mvi

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.nucleusframework.lab.core.ProbeId
import dev.nucleusframework.lab.core.commands.LabCommands
import dev.nucleusframework.lab.core.commands.LabParams
import dev.nucleusframework.lab.core.format.summary
import dev.nucleusframework.lab.core.session.ProbeSessions
import dev.nucleusframework.lab.core.session.SessionHost
import dev.nucleusframework.lab.core.timeline.EntryKind
import dev.nucleusframework.lab.core.timeline.Severity
import dev.nucleusframework.lab.core.timeline.Timeline
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Pure state transition: the only place a screen's state changes. */
fun interface Reducer<S, E> {
    fun reduce(
        state: S,
        event: E,
    ): S
}

/**
 * MVVM shell, MVI flow: the UI sends [I]ntents, [handle] talks to gateways and turns what
 * happened into [E]vents, the [Reducer] folds them into [S]tate, and [F]ffects fire once.
 *
 * Every intent and event lands in the [Timeline]; an event carrying a [Stamped] origin is
 * recorded with the thread the OS delivered it on, which is how an off-UI-thread callback
 * shows up without any probe-specific code.
 */
abstract class MviViewModel<S : Any, I : Any, E : Any, F : Any>(
    initialState: S,
    private val reducer: Reducer<S, E>,
    protected val timeline: Timeline,
    protected val source: ProbeId,
) : ViewModel() {
    private val mutableState = MutableStateFlow(initialState)
    val state: StateFlow<S> = mutableState.asStateFlow()

    private val effectChannel = Channel<F>(Channel.BUFFERED)
    val effects: Flow<F> = effectChannel.receiveAsFlow()

    private val failures =
        CoroutineExceptionHandler { _, error ->
            timeline.record(source, EntryKind.Event, "Unhandled ${error.summary}", Severity.Error)
        }

    fun onIntent(intent: I) {
        timeline.record(source, EntryKind.Intent, describe(intent))
        viewModelScope.launch(failures) { handle(intent) }
    }

    protected abstract suspend fun handle(intent: I)

    /** Reduces [event]; records it from the calling thread. */
    protected fun dispatch(
        event: E,
        severity: Severity = Severity.Info,
    ) {
        timeline.record(source, EntryKind.Event, describe(event), severity)
        mutableState.update { reducer.reduce(it, event) }
    }

    /** Reduces an event whose origin thread was captured where the OS delivered it. */
    protected fun dispatch(
        stamped: Stamped<E>,
        severity: Severity = Severity.Info,
    ) {
        timeline.recordStamped(source, stamped, describe(stamped.value), severity)
        mutableState.update { reducer.reduce(it, stamped.value) }
    }

    /** Reduces without a timeline entry: for mirrors of app state that are not observations. */
    protected fun reduceSilently(event: E) {
        mutableState.update { reducer.reduce(it, event) }
    }

    protected suspend fun emit(effect: F) {
        timeline.record(source, EntryKind.Effect, describe(effect))
        effectChannel.send(effect)
    }

    protected fun launch(block: suspend () -> Unit): Job = viewModelScope.launch(failures) { block() }

    /** Runs blocking work (files, processes, native calls that may stall) off the UI thread. */
    protected suspend fun <T> io(block: suspend () -> T): T = withContext(Dispatchers.IO) { block() }

    /**
     * Re-reads OS state every [periodMillis] (on IO) and reduces it; only values for which
     * [isChange] holds reach the timeline, the others are reduced silently.
     */
    protected fun <V> poll(
        periodMillis: Long,
        read: suspend () -> V,
        toEvent: (V) -> E,
        isChange: (previous: V?, next: V) -> Boolean = { previous, next -> previous != next },
        severity: (V) -> Severity = { Severity.Info },
    ): Job =
        launch {
            var previous: V? = null
            while (true) {
                val next = io { read() }
                if (isChange(previous, next)) dispatch(toEvent(next), severity(next)) else reduceSilently(toEvent(next))
                previous = next
                delay(periodMillis)
            }
        }

    /** Hands each parameter set sent to this probe (deep link, `-Dlab.probe=`) to [block]. */
    protected fun onParams(
        commands: LabCommands,
        block: suspend (LabParams) -> Unit,
    ): Job = launch { commands.consumeParams(source).collect { block(LabParams(it)) } }

    /** This probe's windows, keyed under its id; see [ProbeSessions]. */
    protected fun sessions(host: SessionHost): ProbeSessions = ProbeSessions(host, source, viewModelScope)

    /** Timeline wording; data classes read well as-is. */
    protected open fun describe(value: Any): String = value.toString()
}
