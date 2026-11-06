package dev.nucleusframework.window.tao.scene

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.window.WindowExceptionHandler
import dev.nucleusframework.window.tao.TaoMouseButton
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Platform-agnostic base for the three Tao scene hosts (macOS / Linux /
 * Windows). It carries only the logic that is genuinely identical across all
 * three — the accessibility debounce scheduler and the pointer-button mapping.
 *
 * The heavy machinery (GPU context setup, the render loop, pointer/gesture
 * state, native-view embedding, lifecycle teardown) stays per-platform: it is
 * inherently divergent — Metal vs EGL vs ANGLE, NSView vs GTK vs HWND, three
 * unrelated single-pointer/multi-touch models — not incidental duplication.
 * The one place the shared a11y code touches the platform is the debounced
 * walk dispatch, exposed as the [dispatchA11yWalk] hook.
 */
@OptIn(ExperimentalComposeUiApi::class)
internal abstract class AbstractTaoComposeSceneHost {
    /**
     * Handler for exceptions raised by user code reached through this host —
     * input dispatch, IME callbacks and the accessibility walk. Frames and the
     * scene's own coroutines are covered one level down, by the same handler
     * installed on [TaoSceneBundle], which every platform funnels through.
     *
     * Set once when the window opens, from the
     * [dev.nucleusframework.window.tao.LocalWindowExceptionHandlerFactory] read
     * in the parent composition — a plain field rather than a CompositionLocal
     * read, because it must also cover exceptions that break the composition
     * itself. Volatile: written on the Tao main thread before `attach()`, read
     * on the platform render thread on Windows/Linux.
     */
    @Volatile
    var exceptionHandler: WindowExceptionHandler? = null

    // The SemanticsOwner walk in TaoSemanticsObserver is O(N); during a scroll
    // `onLayoutChange`/`onSemanticsChange` fire every frame, so a per-frame walk
    // stutters scrolling — most visibly once an AX client (PopClip, VoiceOver,
    // Narrator, Orca) is attached. Debouncing collapses a burst of changes into
    // a single walk once activity settles (trailing edge), with a max-wait so
    // sustained activity still refreshes the tree periodically for assistive
    // tech. The tree therefore stays fresh enough for on-demand AX queries
    // without ever running on the per-frame hot path.
    private val a11yScheduler =
        Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "TaoA11yDebounce").apply { isDaemon = true }
        }

    @Volatile
    private var a11yPendingBlock: (() -> Unit)? = null

    @Volatile
    private var a11yFuture: ScheduledFuture<*>? = null
    private var a11yFirstRequestNs = 0L

    // Gate of the walk parked while no AT client listens; resumed by [TaoA11yActivation].
    @Volatile
    private var a11yParkedGate: (() -> Boolean)? = null

    /**
     * Schedules [block] (a SemanticsOwner walk + snapshot push) to run after
     * changes settle. Coalesces a burst of per-frame change notifications into
     * one debounced run; see the field comment above.
     */
    public fun scheduleA11ySync(
        gate: () -> Boolean = { true },
        block: () -> Unit,
    ) {
        if (a11yScheduler.isShutdown) return
        a11yPendingBlock = block
        val now = System.nanoTime()
        if (a11yFirstRequestNs == 0L) a11yFirstRequestNs = now
        val waitedMs = (now - a11yFirstRequestNs) / NANOS_PER_MS
        val delayMs = if (waitedMs >= A11Y_SYNC_MAX_WAIT_MS) 0L else A11Y_SYNC_DEBOUNCE_MS
        scheduleA11yFire(gate, delayMs)
    }

    private fun scheduleA11yFire(
        gate: () -> Boolean,
        delayMs: Long,
    ) {
        a11yFuture?.cancel(false)
        a11yFuture =
            try {
                a11yScheduler.schedule(
                    {
                        if (gate()) {
                            val b = a11yPendingBlock
                            a11yPendingBlock = null
                            a11yFirstRequestNs = 0L
                            if (b != null) {
                                // Hop to the platform UI thread — the walk touches Compose state.
                                // Guarded there, not here: the block runs on the UI thread, which
                                // is where the window's handler is contractually invoked.
                                dispatchA11yWalk { exceptionHandler.catchExceptions(b) }
                            }
                        } else {
                            // No AT client is listening: park the walk instead of
                            // dropping it. An AX client that connects while the scene
                            // is idle would otherwise wait for the NEXT semantics
                            // change — which never comes on a static scene, leaving
                            // the AT with the 1-node seed tree. The native side
                            // signals the activation (NativeTaoBridge.onA11yActivated),
                            // so an idle app stays fully asleep instead of polling.
                            a11yFirstRequestNs = 0L
                            a11yParkedGate = gate
                            TaoA11yActivation.park(this)
                            // An activation signalled before the park would be missed.
                            if (gate()) {
                                TaoA11yActivation.unpark(this)
                                resumeParkedA11ySync()
                            }
                        }
                    },
                    delayMs,
                    TimeUnit.MILLISECONDS,
                )
            } catch (_: RejectedExecutionException) {
                null
            }
    }

    /** Re-runs the parked walk now that an AT client is listening. */
    internal fun resumeParkedA11ySync() {
        val gate = a11yParkedGate ?: return
        a11yParkedGate = null
        if (!a11yScheduler.isShutdown) scheduleA11yFire(gate, 0L)
    }

    /**
     * Runs the debounced semantics walk [block] on the platform's UI thread
     * and requests a redraw. The only per-platform difference in the a11y
     * pipeline: macOS/Windows enqueue on the flushing dispatcher then
     * `window.requestRedraw()`, Linux coalesces the redraw.
     */
    protected abstract fun dispatchA11yWalk(block: () -> Unit)

    /** Cancels any pending walk and stops the debounce executor. Call from `detach()`. */
    protected fun shutdownA11yScheduler() {
        a11yFuture?.cancel(false)
        a11yScheduler.shutdownNow()
        a11yParkedGate = null
        TaoA11yActivation.unpark(this)
    }

    /** Maps a Tao mouse-button code to Compose's [PointerButton]. */
    protected fun mapButton(code: Int): PointerButton =
        when (code) {
            TaoMouseButton.LEFT -> PointerButton.Primary
            TaoMouseButton.RIGHT -> PointerButton.Secondary
            TaoMouseButton.MIDDLE -> PointerButton.Tertiary
            TaoMouseButton.BACK -> PointerButton.Back
            TaoMouseButton.FORWARD -> PointerButton.Forward
            else -> PointerButton.Primary
        }

    protected companion object {
        const val A11Y_SYNC_DEBOUNCE_MS: Long = 120L
        const val A11Y_SYNC_MAX_WAIT_MS: Long = 600L
        private const val NANOS_PER_MS: Long = 1_000_000L
    }
}

/** Hosts whose semantics walk is parked until an AT client connects. */
internal object TaoA11yActivation {
    private val parked = ConcurrentHashMap.newKeySet<AbstractTaoComposeSceneHost>()

    fun park(host: AbstractTaoComposeSceneHost) {
        parked.add(host)
    }

    fun unpark(host: AbstractTaoComposeSceneHost) {
        parked.remove(host)
    }

    /** Called from native when an AT client starts listening. */
    fun onActivated() {
        for (host in parked.toList()) {
            parked.remove(host)
            host.resumeParkedA11ySync()
        }
    }
}
