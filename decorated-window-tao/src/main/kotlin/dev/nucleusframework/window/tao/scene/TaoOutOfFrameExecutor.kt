package dev.nucleusframework.window.tao.scene

import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.platform.PlatformOutOfFrameExecutor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The Tao [PlatformOutOfFrameExecutor]: runs work Compose defers out of the
 * frame being rendered, on the scene thread, before the next frame starts.
 *
 * Compose's only client is `SubcomposeLayout` (lazy lists, grids, pagers):
 * with an executor, a slot scrolled out of view is deactivated after the frame
 * instead of inside it (and not at all if it scrolls back first), which takes
 * the slot's effect disposals off the frame that scrolls it away. Android has
 * always worked this way; Compose Desktop's AWT backend got its own executor
 * in compose-multiplatform-core#3471 (`DesktopPlatformOutOfFrameExecutor`),
 * which this mirrors — that one posts to the AWT EDT, which is not the Compose
 * thread on Tao, so it could not be reused.
 *
 * Two drain points, as upstream: [onBeforeFrame], called by
 * [TaoSceneRenderingScope.render] before anything is recomposed, guarantees
 * the work ran before the next frame; and a [schedule] while no drain is
 * posted posts one to [scope], the scene's own dispatcher, so the work does not
 * wait for a frame that may never come.
 *
 * A block throwing out of a posted drain goes to the scene's exception router
 * and [requestFrame] is called, so the rest runs in the next frame, under the
 * frame's exception handling, instead of waiting for an unrelated one.
 *
 * Measured by `OutOfFrameExecutorHeadfulCases` (Linux, Wayland, 90 Hz): the
 * frame's own work while scrolling drops by ~30 % at p50; draining only before
 * the frame (no posted drain) or in FIFO order measured no better.
 *
 * Confined to the scene thread, like the scene itself. `-Dnucleus.tao.outOfFrameExecutor=false`
 * disables it ([create] returns `null`): `SubcomposeLayout` then deactivates in
 * the frame, as it did before.
 */
@OptIn(InternalComposeUiApi::class)
internal class TaoOutOfFrameExecutor private constructor(
    private val scope: CoroutineScope,
    private val requestFrame: () -> Unit,
) : PlatformOutOfFrameExecutor {
    private val queue = ArrayDeque<() -> Unit>()
    private var isDisposed = false
    private var isDrainPosted = false

    override val hasWorkScheduled: Boolean
        get() = queue.isNotEmpty()

    override fun schedule(block: () -> Unit) {
        if (isDisposed) return
        queue.addLast(block)
        if (!isDrainPosted) {
            isDrainPosted = true
            scope.launch { postedDrain() }
        }
    }

    /** Runs the pending work; must be called before a frame is recomposed. */
    fun onBeforeFrame() {
        if (isDisposed) return
        drain()
    }

    override fun drainScheduledWorkForTest() = drain()

    /** Drops the pending work; later [schedule] calls are ignored. */
    fun dispose() {
        isDisposed = true
        queue.clear()
    }

    private fun postedDrain() {
        isDrainPosted = false
        var completed = false
        try {
            drain()
            completed = true
        } finally {
            // The throw goes on to the scene's exception router; what is left
            // runs before the next frame.
            if (!completed && !isDisposed) requestFrame()
        }
    }

    private fun drain() {
        // Same order as upstream (last scheduled first); a block may schedule
        // more, which runs in this drain too.
        while (!isDisposed && queue.isNotEmpty()) {
            queue.removeLast().invoke()
        }
    }

    companion object {
        private val enabled: Boolean =
            System.getProperty("nucleus.tao.outOfFrameExecutor")?.toBooleanStrictOrNull() ?: true

        /** The scene's executor, or `null` when disabled — see the class KDoc. */
        fun create(
            scope: CoroutineScope,
            requestFrame: () -> Unit,
        ): TaoOutOfFrameExecutor? = if (enabled) TaoOutOfFrameExecutor(scope, requestFrame) else null
    }
}
