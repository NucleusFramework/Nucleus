package dev.nucleusframework.lab.probes.window.shared

import dev.nucleusframework.lab.core.mvi.Stamped
import dev.nucleusframework.window.tao.TaoWindow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Follows the windows a ViewModel opened, one follower per key: attaching a new window under
 * a key replaces the previous follower.
 *
 * Every native signal reaches `onSignal` still [Stamped] with the thread it arrived on, so the
 * ViewModel reduces it with `dispatch(stamped.map { … })` and the timeline judges the thread.
 * A fresh snapshot reaches `onSettled` after each state flip, and once a move / resize burst
 * has been quiet for [settleMillis]: a drag produces one snapshot, not hundreds.
 */
class WindowFollow(
    private val scope: CoroutineScope,
    private val observer: WindowObserver,
    private val settleMillis: Long = DEFAULT_SETTLE_MILLIS,
) {
    private val followers = mutableMapOf<Any, Job>()

    fun follow(
        window: TaoWindow,
        key: Any = Unit,
        onSignal: (Stamped<WindowSignal>) -> Unit = {},
        onSettled: (WindowSnapshot) -> Unit,
    ) {
        followers.remove(key)?.cancel()
        followers[key] = scope.launch { follow(window, onSignal, onSettled) }
    }

    private suspend fun follow(
        window: TaoWindow,
        onSignal: (Stamped<WindowSignal>) -> Unit,
        onSettled: (WindowSnapshot) -> Unit,
    ) = coroutineScope {
        onSettled(observer.snapshot(window))
        var pending: Job? = null
        observer.signals(window).collect { stamped ->
            onSignal(stamped)
            if (stamped.value == WindowSignal.Destroyed) return@collect
            pending?.cancel()
            pending =
                launch {
                    if (stamped.value.isGeometry) delay(settleMillis)
                    onSettled(observer.snapshot(window))
                }
        }
    }

    private companion object {
        const val DEFAULT_SETTLE_MILLIS = 300L
    }
}
