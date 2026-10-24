package dev.nucleusframework.lab.core.mvi

import dev.nucleusframework.lab.core.ProbeId
import dev.nucleusframework.lab.core.timeline.EntryKind
import dev.nucleusframework.lab.core.timeline.Severity
import dev.nucleusframework.lab.core.timeline.Timeline
import dev.nucleusframework.lab.core.timeline.UiThread
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * A value tagged with the thread it was produced on. Gateways stamp OS callbacks at the
 * callback site; by the time a flow is collected on the UI thread that fact is gone.
 */
data class Stamped<out T>(
    val value: T,
    val thread: String,
    val onUiThread: Boolean?,
    /** When the OS delivered it: reducers read this instead of the clock, so they stay pure. */
    val epochMillis: Long = System.currentTimeMillis(),
)

/** Same origin, another value: wrap an OS payload into the probe's event without losing its thread. */
fun <T, R> Stamped<T>.map(transform: (T) -> R): Stamped<R> = Stamped(transform(value), thread, onUiThread, epochMillis)

fun <T> T.stamped(): Stamped<T> {
    val thread = Thread.currentThread()
    return Stamped(this, thread.name, UiThread.isCurrent(thread))
}

/** Stamped with its thread but not judged: for APIs that promise no particular thread. */
fun <T> T.stampedUnjudged(): Stamped<T> = Stamped(this, Thread.currentThread().name, onUiThread = null)

/**
 * `callbackFlow` whose emissions are [Stamped] from the producing thread:
 * `stampedCallbackFlow { emit -> api.listener = { emit(it) }; onClose { api.listener = null } }`.
 */
fun <T> stampedCallbackFlow(register: StampedProducer<T>.() -> Unit): Flow<Stamped<T>> =
    callbackFlow {
        var unregister: () -> Unit = {}
        val producer =
            object : StampedProducer<T> {
                override fun emit(value: T) {
                    trySend(value.stamped())
                }

                override fun onClose(block: () -> Unit) {
                    unregister = block
                }
            }
        producer.register()
        awaitClose { unregister() }
    }

interface StampedProducer<T> {
    fun emit(value: T)

    fun onClose(block: () -> Unit)
}

internal fun Timeline.recordStamped(
    source: ProbeId,
    stamped: Stamped<*>,
    message: String,
    severity: Severity,
) {
    val offUi = stamped.onUiThread == false
    record(
        source,
        EntryKind.Event,
        if (offUi) "$message  ⚠ delivered on ${stamped.thread}" else message,
        if (offUi && severity == Severity.Info) Severity.Warning else severity,
    )
}
