package dev.nucleusframework.application.internal

/**
 * Decides when idle GC should run for the `nucleusOptimization` pack.
 *
 * Serial is stop-the-world, so a collection is only requested when no tracked
 * window is still focused and visible. A minimized window collects immediately;
 * a mere focus loss waits [UNFOCUS_DELAY_MS] so alt-tab / click-away that
 * comes back quickly does not hitch.
 *
 * The first snapshot for a window (registration) never triggers a collection,
 * so a window that starts unfocused before its first paint cannot GC during
 * startup.
 */
internal class IdleGcController {
    private val lock = Any()
    private val windows = LinkedHashMap<Any, WindowIdle>()
    private var deferredArmed: Boolean = false

    fun register(
        id: Any,
        focused: Boolean,
        minimized: Boolean,
    ) {
        synchronized(lock) {
            windows[id] = WindowIdle(focused, minimized)
        }
    }

    fun unregister(id: Any): IdleGcCommand =
        synchronized(lock) {
            if (windows.remove(id) == null) IdleGcCommand.NoChange else commit(decide())
        }

    fun update(
        id: Any,
        focused: Boolean,
        minimized: Boolean,
    ): IdleGcCommand =
        synchronized(lock) {
            val next = WindowIdle(focused, minimized)
            val prev = windows[id] ?: return@synchronized IdleGcCommand.NoChange
            if (prev == next) return@synchronized IdleGcCommand.NoChange
            windows[id] = next
            commit(decide())
        }

    /**
     * True when a deferred (unfocus) collection was armed and is still valid:
     * every tracked window is unfocused and none is minimized. Minimize already
     * collected immediately, so the delay must not fire a second time.
     */
    fun shouldRunDeferredGc(): Boolean =
        synchronized(lock) {
            deferredArmed && decide() == IdleGcCommand.Debounce
        }

    private fun decide(): IdleGcCommand {
        if (windows.isEmpty() || windows.values.any { it.isInteracting }) {
            return IdleGcCommand.Cancel
        }
        if (windows.values.any { it.minimized }) {
            return IdleGcCommand.CollectNow
        }
        return IdleGcCommand.Debounce
    }

    private fun commit(cmd: IdleGcCommand): IdleGcCommand {
        when (cmd) {
            IdleGcCommand.Debounce -> deferredArmed = true
            IdleGcCommand.Cancel, IdleGcCommand.CollectNow -> deferredArmed = false
            IdleGcCommand.NoChange -> Unit
        }
        return cmd
    }

    private data class WindowIdle(
        val focused: Boolean,
        val minimized: Boolean,
    ) {
        val isInteracting: Boolean get() = focused && !minimized
    }

    companion object {
        const val UNFOCUS_DELAY_MS: Long = 3_000
    }
}

internal enum class IdleGcCommand {
    NoChange,
    Cancel,
    Debounce,
    CollectNow,
}
