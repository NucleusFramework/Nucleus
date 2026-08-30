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
 * calls from inside [render] or a [nonReentrant] block are posted, not
 * re-entered.
 */
internal class StandaloneFramePump(
    private val isOnMain: () -> Boolean = {
        Thread.currentThread() === TaoMainDispatcher.taoMainThread
    },
    private val post: (Runnable) -> Unit = { block ->
        TaoMainDispatcher.dispatch(EmptyCoroutineContext, block)
    },
    private val render: () -> Unit,
) {
    private val pending = AtomicBoolean(false)

    // Only the thread [isOnMain] accepted for an inline render, and the
    // thread [post] delivers to (production: both are Tao main). Tests
    // inject both so they never touch [TaoMainDispatcher.taoMainThread].
    private var rendering = false

    @Volatile
    var disposed: Boolean = false

    fun schedule() {
        if (disposed) return
        if (!pending.compareAndSet(false, true)) return
        if (isOnMain() && !rendering) {
            runRender()
        } else {
            post(Runnable { runRender() })
        }
    }

    /**
     * Runs [block] with inline rendering suppressed: a [schedule] arriving
     * while the block is on the stack is posted instead of run re-entrantly.
     *
     * Wraps every scene entry point of the standalone hosts (pointer, scroll
     * and key dispatch, `setContent`): a scrollbar drag forces a measure pass
     * synchronously inside the pointer event, and a coroutine dispatched from
     * within that pass (the `LaunchedEffect` of a freshly subcomposed lazy
     * item) lands in the scene dispatcher, whose dispatch schedules a frame.
     * Rendering that frame inline would call `measureAndLayout` while the
     * first pass is still on the stack — Compose throws
     * `performMeasureAndLayout called during measure layout`.
     */
    fun <T> nonReentrant(block: () -> T): T {
        val outer = rendering
        rendering = true
        try {
            return block()
        } finally {
            rendering = outer
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
