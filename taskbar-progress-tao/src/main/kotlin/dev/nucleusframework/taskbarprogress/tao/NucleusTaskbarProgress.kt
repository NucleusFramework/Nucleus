package dev.nucleusframework.taskbarprogress.tao

import dev.nucleusframework.application.NucleusWindow
import dev.nucleusframework.taskbarprogress.TaskbarProgress
import java.util.concurrent.Executors

/**
 * Taskbar/dock façade taking a [NucleusWindow] and dispatching to
 * [TaoTaskbarProgress].
 *
 * App code should prefer this entry point over the backend-specific objects:
 * call sites stay portable if the window type ever changes.
 *
 * **Threading**: every call is offloaded to a dedicated daemon worker. The
 * underlying Windows API (`ITaskbarList3`) internally uses `SendMessage` and
 * would otherwise freeze the caller if invoked while the owning event loop
 * is being torn down (typical during `DisposableEffect.onDispose` on app
 * close). Because the operations are purely visual hints, the boolean return
 * value reports best-effort dispatch (queued vs. impossible), not completion.
 */
public object NucleusTaskbarProgress {
    private val worker =
        Executors.newSingleThreadExecutor { r ->
            Thread(r, "nucleus-taskbar-progress").apply { isDaemon = true }
        }

    public fun isAvailable(): Boolean = TaskbarProgress.isAvailable()

    public fun setProgress(
        window: NucleusWindow,
        value: Double,
    ): Boolean =
        dispatch(
            window,
            tao = { TaoTaskbarProgress.setProgress(it, value) },
        )

    public fun setState(
        window: NucleusWindow,
        state: TaskbarProgress.State,
    ): Boolean =
        dispatch(
            window,
            tao = { TaoTaskbarProgress.setState(it, state) },
        )

    public fun showProgress(
        window: NucleusWindow,
        value: Double,
    ): Boolean =
        dispatch(
            window,
            tao = { TaoTaskbarProgress.showProgress(it, value) },
        )

    public fun showError(
        window: NucleusWindow,
        value: Double = 1.0,
    ): Boolean =
        dispatch(
            window,
            tao = { TaoTaskbarProgress.showError(it, value) },
        )

    public fun showIndeterminate(window: NucleusWindow): Boolean =
        dispatch(
            window,
            tao = { TaoTaskbarProgress.showIndeterminate(it) },
        )

    public fun showPaused(
        window: NucleusWindow,
        value: Double = 1.0,
    ): Boolean =
        dispatch(
            window,
            tao = { TaoTaskbarProgress.showPaused(it, value) },
        )

    public fun hideProgress(window: NucleusWindow): Boolean =
        dispatch(
            window,
            tao = { TaoTaskbarProgress.hideProgress(it) },
        )

    public fun requestAttention(
        window: NucleusWindow,
        type: TaskbarProgress.AttentionType = TaskbarProgress.AttentionType.INFORMATIONAL,
    ): Boolean =
        dispatch(
            window,
            tao = { TaoTaskbarProgress.requestAttention(it, type) },
        )

    public fun stopAttention(window: NucleusWindow): Boolean =
        dispatch(
            window,
            tao = { TaoTaskbarProgress.stopAttention(it) },
        )

    private inline fun dispatch(
        window: NucleusWindow,
        crossinline tao: (dev.nucleusframework.window.tao.TaoWindow) -> Boolean,
    ): Boolean {
        val taoWindow = window.unsafe.taoWindow ?: return false
        worker.submit { runCatching { tao(taoWindow) } }
        return true
    }
}

// Ergonomic extensions: `nucleusWindow.setTaskbarProgress(0.5)` reads naturally
// at the call site and removes the need to repeat the [NucleusTaskbarProgress]
// receiver.
public fun NucleusWindow.setTaskbarProgress(value: Double): Boolean = NucleusTaskbarProgress.setProgress(this, value)

public fun NucleusWindow.setTaskbarState(state: TaskbarProgress.State): Boolean =
    NucleusTaskbarProgress.setState(this, state)

public fun NucleusWindow.showTaskbarProgress(value: Double): Boolean = NucleusTaskbarProgress.showProgress(this, value)

public fun NucleusWindow.showTaskbarError(value: Double = 1.0): Boolean = NucleusTaskbarProgress.showError(this, value)

public fun NucleusWindow.showTaskbarIndeterminate(): Boolean = NucleusTaskbarProgress.showIndeterminate(this)

public fun NucleusWindow.showTaskbarPaused(value: Double = 1.0): Boolean =
    NucleusTaskbarProgress.showPaused(this, value)

public fun NucleusWindow.hideTaskbarProgress(): Boolean = NucleusTaskbarProgress.hideProgress(this)

public fun NucleusWindow.requestTaskbarAttention(
    type: TaskbarProgress.AttentionType = TaskbarProgress.AttentionType.INFORMATIONAL,
): Boolean = NucleusTaskbarProgress.requestAttention(this, type)

public fun NucleusWindow.stopTaskbarAttention(): Boolean = NucleusTaskbarProgress.stopAttention(this)
