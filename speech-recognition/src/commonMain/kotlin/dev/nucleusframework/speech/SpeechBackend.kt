package dev.nucleusframework.speech

import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.AtomicReference

/** One platform's recognizer. Reports every event of session `id` through [SpeechSessions.emit]. */
internal interface SpeechBackend {
    val isSupported: Boolean
    val engineName: String

    /** Starts session [id] without blocking. Throws [SpeechException] if it cannot even start. */
    fun start(
        id: Long,
        options: SpeechRecognitionOptions,
    )

    /** Ends session [id]: gracefully (the last result still arrives), or discarding everything. */
    fun stop(
        id: Long,
        cancel: Boolean,
    )
}

internal expect fun platformSpeechBackend(): SpeechBackend

/** Reports a listener that threw; it must not unwind into the platform's callback. */
internal expect fun reportListenerFailure(failure: Throwable)

/**
 * The listeners of live sessions, keyed by id, shared by every backend. A terminal event
 * removes its session, so late platform callbacks (and a previous session's) are dropped.
 */
internal object SpeechSessions {
    private val listeners = AtomicReference<Map<Long, (SpeechEvent) -> Unit>>(emptyMap())
    private val ids = AtomicLong(1)

    fun nextId(): Long = ids.fetchAndAdd(1)

    /** Registers [id] unless another session is live. */
    fun register(
        id: Long,
        listener: (SpeechEvent) -> Unit,
    ): Boolean {
        while (true) {
            val current = listeners.load()
            if (current.isNotEmpty()) return false
            if (listeners.compareAndSet(current, mapOf(id to listener))) return true
        }
    }

    fun unregister(id: Long): ((SpeechEvent) -> Unit)? {
        while (true) {
            val current = listeners.load()
            val listener = current[id] ?: return null
            if (listeners.compareAndSet(current, current - id)) return listener
        }
    }

    fun unregisterAll(): Set<Long> = listeners.exchange(emptyMap()).keys

    fun isActive(id: Long): Boolean = id in listeners.load()

    fun emit(
        id: Long,
        event: SpeechEvent,
    ) {
        val listener = if (event.isTerminal) unregister(id) else listeners.load()[id]
        listener ?: return
        try {
            listener(event)
        } catch (
            @Suppress("TooGenericExceptionCaught") failure: Throwable,
        ) {
            reportListenerFailure(failure)
        }
    }
}
