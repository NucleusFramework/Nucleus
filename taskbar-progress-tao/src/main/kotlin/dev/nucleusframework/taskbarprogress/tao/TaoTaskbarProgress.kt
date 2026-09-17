package dev.nucleusframework.taskbarprogress.tao

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import dev.nucleusframework.core.runtime.Platform
import dev.nucleusframework.taskbarprogress.TaskbarProgress
import dev.nucleusframework.window.tao.LocalTaoWindow
import dev.nucleusframework.window.tao.TaoWindow

/**
 * Tao-backend façade over [TaskbarProgress]. On Windows it resolves the HWND
 * via [TaoWindow.nativeHandle]; on macOS/Linux the [TaoWindow] is unused
 * (NSDockTile is app-wide; Linux uses the `.desktop` filename) and the call
 * delegates straight to the corresponding platform branch.
 */
public object TaoTaskbarProgress {
    public fun isAvailable(): Boolean = TaskbarProgress.isAvailable()

    public fun setProgress(
        window: TaoWindow,
        value: Double,
    ): Boolean = TaskbarProgress.setProgressForHwnd(resolveHwnd(window) ?: return false, value)

    public fun setState(
        window: TaoWindow,
        state: TaskbarProgress.State,
    ): Boolean = TaskbarProgress.setStateForHwnd(resolveHwnd(window) ?: return false, state)

    public fun showProgress(
        window: TaoWindow,
        value: Double,
    ): Boolean = setState(window, TaskbarProgress.State.NORMAL) && setProgress(window, value)

    public fun showError(
        window: TaoWindow,
        value: Double = 1.0,
    ): Boolean = setState(window, TaskbarProgress.State.ERROR) && setProgress(window, value)

    public fun showIndeterminate(window: TaoWindow): Boolean = setState(window, TaskbarProgress.State.INDETERMINATE)

    public fun showPaused(
        window: TaoWindow,
        value: Double = 1.0,
    ): Boolean = setState(window, TaskbarProgress.State.PAUSED) && setProgress(window, value)

    public fun hideProgress(window: TaoWindow): Boolean = setState(window, TaskbarProgress.State.NO_PROGRESS)

    public fun requestAttention(
        window: TaoWindow,
        type: TaskbarProgress.AttentionType = TaskbarProgress.AttentionType.INFORMATIONAL,
    ): Boolean = TaskbarProgress.requestAttentionForHwnd(resolveHwnd(window) ?: return false, type)

    public fun stopAttention(window: TaoWindow): Boolean =
        TaskbarProgress.stopAttentionForHwnd(resolveHwnd(window) ?: return false)

    /**
     * Returns the HWND on Windows; on macOS/Linux returns a sentinel `0` since
     * the underlying APIs are window-agnostic. Returns `null` only if Windows
     * fails to resolve a real HWND.
     */
    private fun resolveHwnd(window: TaoWindow): Long? =
        when (Platform.Current) {
            Platform.Windows -> window.nativeHandle.takeIf { it != 0L }
            else -> 0L
        }
}

/**
 * Composable handle bound to the current [LocalTaoWindow]. Returns `null`
 * when called outside of a Tao `DecoratedWindow` content lambda.
 */
@Composable
public fun rememberTaoTaskbarProgress(): TaoTaskbarProgressScope? {
    val window = LocalTaoWindow.current ?: return null
    return remember(window) { TaoTaskbarProgressScope(window) }
}

public class TaoTaskbarProgressScope internal constructor(
    public val window: TaoWindow,
) {
    public fun setProgress(value: Double): Boolean = TaoTaskbarProgress.setProgress(window, value)

    public fun setState(state: TaskbarProgress.State): Boolean = TaoTaskbarProgress.setState(window, state)

    public fun showProgress(value: Double): Boolean = TaoTaskbarProgress.showProgress(window, value)

    public fun showError(value: Double = 1.0): Boolean = TaoTaskbarProgress.showError(window, value)

    public fun showIndeterminate(): Boolean = TaoTaskbarProgress.showIndeterminate(window)

    public fun showPaused(value: Double = 1.0): Boolean = TaoTaskbarProgress.showPaused(window, value)

    public fun hideProgress(): Boolean = TaoTaskbarProgress.hideProgress(window)

    public fun requestAttention(
        type: TaskbarProgress.AttentionType = TaskbarProgress.AttentionType.INFORMATIONAL,
    ): Boolean = TaoTaskbarProgress.requestAttention(window, type)

    public fun stopAttention(): Boolean = TaoTaskbarProgress.stopAttention(window)
}
