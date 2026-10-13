package dev.nucleusframework.window.tao.scene

import dev.nucleusframework.window.tao.dispatch.TaoMainDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/**
 * The dispatcher a Linux / Windows scene's coroutines run on.
 *
 * Its queue is drained twice over: by the host's frame path, before the frame
 * clock ticks, so `withFrameNanos` continuations apply on the frame that
 * resumed them; and between frames, on the event loop through
 * [TaoMainDispatcher]. A dispatch therefore never asks for a frame of its own
 * (#754): a main-confined `delay` loop, a clock, a `withContext(Dispatchers.Main)`
 * hop used to render and present a full frame each time, although nothing on
 * screen changed. What does change the screen invalidates the scene, and the
 * scene's own invalidation requests the frame.
 *
 * Event-loop thread only for [drain]; [dispatch] / [enqueue] from any thread.
 */
internal class SceneMainDispatcher : CoroutineDispatcher() {
    private val queue = ConcurrentLinkedQueue<Runnable>()

    /** An off-frame drain is posted and has not started yet. */
    private val drainPosted = AtomicBoolean(false)

    private val offFrameDrain =
        Runnable {
            // Cleared first: a block dispatched while this drain runs posts the next one.
            drainPosted.set(false)
            drain()
        }

    override fun dispatch(
        context: CoroutineContext,
        block: Runnable,
    ) {
        enqueue(block)
    }

    /** Same as [dispatch], for raw framework blocks. */
    fun enqueue(block: Runnable) {
        queue.add(block)
        postDrain()
    }

    /**
     * Runs what is queued now. Bounded by the queue's size on entry, so a
     * continuation that re-dispatches itself cannot spin the caller; what it
     * queued runs on the next off-frame drain, which [TaoMainDispatcher]
     * throttles like any other self-redispatch.
     */
    fun drain() {
        try {
            var remaining = queue.size
            while (remaining-- > 0) {
                val runnable = queue.poll() ?: break
                runnable.run()
            }
        } finally {
            // Also when a raw block threw: what is left behind must not wait for the next dispatch.
            if (!queue.isEmpty()) postDrain()
        }
    }

    private fun postDrain() {
        if (drainPosted.compareAndSet(false, true)) {
            TaoMainDispatcher.dispatch(EmptyCoroutineContext, offFrameDrain)
        }
    }
}
