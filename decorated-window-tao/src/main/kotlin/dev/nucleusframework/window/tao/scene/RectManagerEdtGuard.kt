// Static access to Compose internals (same technique as TaoWindowsScrollConfig):
// plain bytecode, so it stays GraalVM native-image compatible - no reflection.
@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE", "KotlinRedundantDiagnosticSuppress")

package dev.nucleusframework.window.tao.scene

import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.platform.PlatformContext
import androidx.compose.ui.semantics.SemanticsOwner
import androidx.compose.ui.spatial.RectManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Keeps Compose 1.12's `RectManager` delayed dispatch off the AWT EDT
 * (issue #551).
 *
 * `RectManager` (the spatial index behind `onLayoutRectChanged` and
 * focus-by-rect) debounces its callback dispatch with `postDelayed`, whose
 * desktop implementation launches on skiko's `MainUIDispatcher` — a hardcoded
 * Swing/EDT dispatcher. On the AWT backends the EDT *is* the Compose thread,
 * so that delayed `dispatchCallbacks()` is serialized with everything else.
 * On Tao the Compose thread is the native event-loop thread: whenever the
 * ~16ms timer fires before the next pass cancels it (an idle gap between
 * frames, or a layout pass longer than the debounce deadline — a large tab
 * remount), the EDT runs `RectManager.dispatchCallbacks()` — including
 * `RectList.defragment()`, which swaps and compacts the backing arrays —
 * concurrently with (or without any happens-before edge to) layout running on
 * the scene thread. The unsynchronized race corrupts the RectList and later
 * surfaces as `IllegalArgumentException: LayoutNode not found in RectList`
 * from `LayoutNode.detach` (or an `ArrayIndexOutOfBoundsException` from the
 * same bookkeeping), killing the app. `TaoSceneRectManagerRaceTest`
 * reproduces it deterministically.
 *
 * Compose offers no scheduling hook here, so the guard neutralizes the timer
 * instead: it pins `ThrottledCallbacks.minDebounceDeadline` to a far-future
 * instant while the scene is between passes. Every dispatch Compose arms then
 * carries that unreachable deadline (`scheduleDebounceCallback` uses
 * `max(minDebounceDeadline, now + 16)`), so the armed EDT coroutine can never
 * fire — no RectManager code ever executes off the scene thread. The pinned
 * value never leaks into callback bookkeeping: `triggerDebounced` returns
 * before touching entries while the deadline is in the future, and the fire
 * paths only ever *lower* the deadline to a real one when an actual debounced
 * callback needs the trailing edge.
 *
 * The debounce feature itself is preserved by [afterFrame], which runs after
 * every rendered frame on the scene thread: when `onLayoutRectChanged` /
 * `onGlobalLayoutListener` entries exist it momentarily unpins the deadline
 * and runs `dispatchCallbacks()` — firing due trailing edges exactly like the
 * upstream timer would, only on the right thread — then re-pins and schedules
 * a frame for the next real deadline.
 *
 * Coverage: on Compose 1.12.0-rc01 the pin is believed complete even for apps
 * registering debounced rect callbacks (`Modifier.onLayoutRectChanged` /
 * `onFirstVisible` / `onVisibilityChanged`), because the pinned deadline can
 * never be lowered outside [afterFrame]: `triggerDebounced` returns before
 * recomputing while the deadline is in the future, and the mid-pass lowering
 * branch in `ThrottledCallbacks.fireWithUpdatedRect`/`fire` assigns the OLD
 * deadline back (an upstream no-op — `minDebounceDeadline = currentMinDeadline`
 * where `thisDeadline` was clearly intended). [onScenePulse] is kept as
 * defense-in-depth for the day upstream fixes that assignment: it re-pins as
 * soon as a real (lowered) deadline becomes observable on the scene thread.
 * `examples/rect-stress-demo` is the standing sentinel: it hammers exactly
 * this path with a wrong-thread detector in the callback and must never
 * trigger it.
 */
@OptIn(InternalComposeUiApi::class)
internal class RectManagerEdtGuard(
    private val scope: CoroutineScope,
    private val requestFrame: () -> Unit,
) {
    private val rectManagers = ArrayList<RectManager>(2)
    private var wakeupJob: Job? = null

    /**
     * Wraps [delegate] so every [SemanticsOwner] the scene announces — the
     * main owner and each popup/dialog layer — registers its RectManager with
     * this guard. The scene announces an owner right after constructing it,
     * before any content is set.
     */
    fun wrapListener(delegate: PlatformContext.SemanticsOwnerListener?): PlatformContext.SemanticsOwnerListener =
        object : PlatformContext.SemanticsOwnerListener {
            override fun onSemanticsOwnerAppended(semanticsOwner: SemanticsOwner) {
                onOwnerAppended(semanticsOwner)
                delegate?.onSemanticsOwnerAppended(semanticsOwner)
            }

            override fun onSemanticsOwnerRemoved(semanticsOwner: SemanticsOwner) {
                onOwnerRemoved(semanticsOwner)
                delegate?.onSemanticsOwnerRemoved(semanticsOwner)
            }

            override fun onSemanticsChange(semanticsOwner: SemanticsOwner) {
                onScenePulse()
                delegate?.onSemanticsChange(semanticsOwner)
            }

            override fun onLayoutChange(
                semanticsOwner: SemanticsOwner,
                semanticsNodeId: Int,
            ) {
                onScenePulse()
                delegate?.onLayoutChange(semanticsOwner, semanticsNodeId)
            }
        }

    /**
     * Post-frame hook, run on the scene thread after every rendered frame:
     * fires due debounced callbacks (when any are registered), cancels
     * whatever dispatch the pass armed, and re-pins the deadline so anything
     * armed until the next frame stays inert.
     */
    fun afterFrame() {
        var nextWakeupDelayMillis = -1L
        for (i in rectManagers.indices) {
            val rectManager = rectManagers[i]
            val throttled = rectManager.throttledCallbacks
            val hasEntries =
                throttled.rectChangedMap.size != 0 || throttled.globalChangeEntries != null
            if (hasEntries) {
                // Unpin and run one dispatch: triggerDebounced fires the due
                // trailing edges and recomputes the real next deadline.
                val now = System.currentTimeMillis()
                throttled.minDebounceDeadline = 0
                rectManager.dispatchCallbacks()
                val deadline = throttled.minDebounceDeadline
                if (deadline > 0) {
                    val delayMillis = (deadline - now).coerceAtLeast(1)
                    if (nextWakeupDelayMillis < 0 || delayMillis < nextWakeupDelayMillis) {
                        nextWakeupDelayMillis = delayMillis
                    }
                }
            }
            disarm(rectManager)
        }
        if (nextWakeupDelayMillis >= 0) {
            // A debounced trailing edge is pending: wake the frame loop when
            // it is due so the next afterFrame fires it on the scene thread.
            wakeupJob?.cancel()
            wakeupJob =
                scope.launch(start = CoroutineStart.UNDISPATCHED) {
                    delay(nextWakeupDelayMillis)
                    requestFrame()
                }
        }
    }

    /**
     * Mid-pass check, run from the layout/semantics-change callbacks that fire
     * on the scene thread while a pass is still running: a debounced rect
     * callback firing during the pass lowers the pinned deadline back to a
     * real one, and the pass then re-arms a dispatch that *can* elapse
     * (`max(realDeadline, now + 16)`). Re-pin as soon as we can observe it, so
     * such a dispatch only survives until the next semantics/layout
     * notification instead of the end of the frame. No deadline information is
     * lost: [afterFrame] recomputes the real deadline from the registered
     * entries. When the deadline is already pinned (or there is none), this is
     * two field reads — cheap enough for a per-node callback.
     */
    private fun onScenePulse() {
        for (i in rectManagers.indices) {
            val rectManager = rectManagers[i]
            val deadline = rectManager.throttledCallbacks.minDebounceDeadline
            if (deadline > 0 && deadline != INERT_DEADLINE_MILLIS) {
                disarm(rectManager)
            }
        }
    }

    private fun onOwnerAppended(semanticsOwner: SemanticsOwner) {
        val owner = semanticsOwner.rootSemanticsNode.layoutNode.owner ?: return
        val rectManager = owner.rectManager
        rectManagers.add(rectManager)
        // Kill the dispatch armed while the owner attached its root, then pin.
        disarm(rectManager)
    }

    private fun onOwnerRemoved(semanticsOwner: SemanticsOwner) {
        val owner = semanticsOwner.rootSemanticsNode.layoutNode.owner ?: return
        rectManagers.remove(owner.rectManager)
    }

    private fun disarm(rectManager: RectManager) {
        rectManager.removeScheduledCallback()
        rectManager.throttledCallbacks.minDebounceDeadline = INERT_DEADLINE_MILLIS
    }

    private companion object {
        /**
         * The pinned deadline: far enough that `max(deadline, now + 16)` never
         * elapses, small enough that millisecond arithmetic on it cannot
         * overflow. (~292 million years of uptime.)
         */
        const val INERT_DEADLINE_MILLIS = Long.MAX_VALUE / 1024
    }
}
