package dev.nucleusframework.window.tao.popup

import dev.nucleusframework.window.tao.dispatch.TaoMainDispatcher
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.EmptyCoroutineContext

/**
 * Coalesced on-demand frames for a standalone popup. Every scene
 * `requestFrame` (scroll, pointer, animation) goes through [schedule] —
 * there is no separate wheel paint path.
 *
 * Runs [render] immediately when already on the Tao main thread and not
 * already inside a frame. A WndProc / NSEvent flood otherwise starves
 * `MainEventsCleared` and a queued frame never paints. Nested [schedule]
 * calls from inside [render] are posted, not re-entered.
 */
internal class StandaloneFramePump(
    private val render: () -> Unit,
) {
    private val pending = AtomicBoolean(false)
    private var rendering = false
    var disposed: Boolean = false

    fun schedule() {
        if (disposed) return
        if (!pending.compareAndSet(false, true)) return
        val onMain = Thread.currentThread() === TaoMainDispatcher.taoMainThread
        if (onMain && !rendering) {
            runRender()
        } else {
            TaoMainDispatcher.dispatch(EmptyCoroutineContext) { runRender() }
        }
    }

    private fun runRender() {
        pending.set(false)
        if (disposed) return
        rendering = true
        try {
            render()
        } finally {
            rendering = false
        }
    }
}
