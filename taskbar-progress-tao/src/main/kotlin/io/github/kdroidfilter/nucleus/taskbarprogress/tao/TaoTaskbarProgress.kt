package io.github.kdroidfilter.nucleus.taskbarprogress.tao

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import io.github.kdroidfilter.nucleus.core.runtime.Platform
import io.github.kdroidfilter.nucleus.taskbarprogress.TaskbarProgress
import io.github.kdroidfilter.nucleus.window.tao.LocalTaoWindow
import io.github.kdroidfilter.nucleus.window.tao.TaoWindow

/**
 * Tao-backend façade over [TaskbarProgress]. On Windows it resolves the HWND
 * via [TaoWindow.nativeHandle]; on macOS/Linux the [TaoWindow] is unused
 * (NSDockTile is app-wide; Linux uses the `.desktop` filename) and the call
 * delegates straight to the corresponding platform branch.
 */
object TaoTaskbarProgress {
    fun isAvailable(): Boolean = TaskbarProgress.isAvailable()

    fun setProgress(window: TaoWindow, value: Double): Boolean =
        TaskbarProgress.setProgressForHwnd(resolveHwnd(window) ?: return false, value)

    fun setState(window: TaoWindow, state: TaskbarProgress.State): Boolean =
        TaskbarProgress.setStateForHwnd(resolveHwnd(window) ?: return false, state)

    fun showProgress(window: TaoWindow, value: Double): Boolean =
        setState(window, TaskbarProgress.State.NORMAL) && setProgress(window, value)

    fun showError(window: TaoWindow, value: Double = 1.0): Boolean =
        setState(window, TaskbarProgress.State.ERROR) && setProgress(window, value)

    fun showIndeterminate(window: TaoWindow): Boolean =
        setState(window, TaskbarProgress.State.INDETERMINATE)

    fun showPaused(window: TaoWindow, value: Double = 1.0): Boolean =
        setState(window, TaskbarProgress.State.PAUSED) && setProgress(window, value)

    fun hideProgress(window: TaoWindow): Boolean =
        setState(window, TaskbarProgress.State.NO_PROGRESS)

    fun requestAttention(
        window: TaoWindow,
        type: TaskbarProgress.AttentionType = TaskbarProgress.AttentionType.INFORMATIONAL,
    ): Boolean = TaskbarProgress.requestAttentionForHwnd(resolveHwnd(window) ?: return false, type)

    fun stopAttention(window: TaoWindow): Boolean =
        TaskbarProgress.stopAttentionForHwnd(resolveHwnd(window) ?: return false)

    /**
     * Returns the HWND on Windows; on macOS/Linux returns a sentinel `0` since
     * the underlying APIs are window-agnostic. Returns `null` only if Windows
     * fails to resolve a real HWND.
     */
    private fun resolveHwnd(window: TaoWindow): Long? = when (Platform.Current) {
        Platform.Windows -> window.nativeHandle.takeIf { it != 0L }
        else -> 0L
    }
}

/**
 * Composable handle bound to the current [LocalTaoWindow]. Returns `null`
 * when called outside of a Tao `DecoratedWindow` content lambda.
 */
@Composable
fun rememberTaoTaskbarProgress(): TaoTaskbarProgressScope? {
    val window = LocalTaoWindow.current ?: return null
    return remember(window) { TaoTaskbarProgressScope(window) }
}

class TaoTaskbarProgressScope internal constructor(val window: TaoWindow) {
    fun setProgress(value: Double) = TaoTaskbarProgress.setProgress(window, value)
    fun setState(state: TaskbarProgress.State) = TaoTaskbarProgress.setState(window, state)
    fun showProgress(value: Double) = TaoTaskbarProgress.showProgress(window, value)
    fun showError(value: Double = 1.0) = TaoTaskbarProgress.showError(window, value)
    fun showIndeterminate() = TaoTaskbarProgress.showIndeterminate(window)
    fun showPaused(value: Double = 1.0) = TaoTaskbarProgress.showPaused(window, value)
    fun hideProgress() = TaoTaskbarProgress.hideProgress(window)
    fun requestAttention(
        type: TaskbarProgress.AttentionType = TaskbarProgress.AttentionType.INFORMATIONAL,
    ) = TaoTaskbarProgress.requestAttention(window, type)
    fun stopAttention() = TaoTaskbarProgress.stopAttention(window)
}
