package io.github.kdroidfilter.nucleus.window.tao

import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher

/**
 * Coroutine dispatcher that posts blocks onto the Tao main thread.
 *
 * Single-threaded model: blocks are queued in [pending] and drained by [pump]
 * on every `Event::MainEventsCleared` tick of the Tao event loop. Both
 * dispatch sites and pump call sites *must* run on the macOS main thread.
 *
 * Threading guarantee: [dispatch] is safe to call from any thread (the queue
 * is thread-safe), but [pump] must be invoked from the main thread only —
 * [TaoApplication] arranges that via the JNI event callback.
 */
internal object TaoMainDispatcher : CoroutineDispatcher() {
    private val pending = ConcurrentLinkedQueue<Runnable>()

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        pending.offer(block)
    }

    /** Drains everything currently pending. New blocks dispatched while
     *  draining run on the next pump (no recursion). */
    fun pump() {
        // Snapshot count to avoid an infinite loop if a block re-dispatches
        // synchronously and re-queues itself.
        var remaining = pending.size
        while (remaining-- > 0) {
            val block = pending.poll() ?: break
            try {
                block.run()
            } catch (t: Throwable) {
                // Coroutine dispatchers swallow exceptions thrown synchronously
                // from `run()`; the runtime reports them via the Recomposer's
                // exception handler. Re-throwing here would crash the Tao loop.
                t.printStackTrace()
            }
        }
    }
}
