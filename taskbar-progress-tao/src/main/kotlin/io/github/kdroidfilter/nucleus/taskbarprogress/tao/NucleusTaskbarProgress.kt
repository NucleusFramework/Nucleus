package io.github.kdroidfilter.nucleus.taskbarprogress.tao

import io.github.kdroidfilter.nucleus.application.NucleusWindow
import io.github.kdroidfilter.nucleus.taskbarprogress.TaskbarProgress
import java.util.concurrent.Executors

/**
 * Backend-agnostic taskbar/dock façade taking a [NucleusWindow]. Dispatches
 * to the AWT-typed [TaskbarProgress] when the window is AWT-backed (JBR / JNI
 * decorated windows) or to [TaoTaskbarProgress] when it is Tao-backed.
 *
 * App code should prefer this entry point over the backend-specific objects:
 * a project can swap backends without touching call sites.
 *
 * **Threading**: every call is offloaded to a dedicated daemon worker. The
 * underlying Windows API (`ITaskbarList3`) internally uses `SendMessage` and
 * would otherwise freeze the caller if invoked while the owning event loop
 * is being torn down (typical during `DisposableEffect.onDispose` on app
 * close). Because the operations are purely visual hints, the boolean return
 * value reports best-effort dispatch (queued vs. impossible), not completion.
 */
object NucleusTaskbarProgress {
    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread(r, "nucleus-taskbar-progress").apply { isDaemon = true }
    }

    fun isAvailable(): Boolean = TaskbarProgress.isAvailable()

    fun setProgress(window: NucleusWindow, value: Double): Boolean = dispatch(
        window,
        awt = { TaskbarProgress.setProgress(it, value) },
        tao = { TaoTaskbarProgress.setProgress(it, value) },
    )

    fun setState(window: NucleusWindow, state: TaskbarProgress.State): Boolean = dispatch(
        window,
        awt = { TaskbarProgress.setState(it, state) },
        tao = { TaoTaskbarProgress.setState(it, state) },
    )

    fun showProgress(window: NucleusWindow, value: Double): Boolean = dispatch(
        window,
        awt = { TaskbarProgress.showProgress(it, value) },
        tao = { TaoTaskbarProgress.showProgress(it, value) },
    )

    fun showError(window: NucleusWindow, value: Double = 1.0): Boolean = dispatch(
        window,
        awt = { TaskbarProgress.showError(it, value) },
        tao = { TaoTaskbarProgress.showError(it, value) },
    )

    fun showIndeterminate(window: NucleusWindow): Boolean = dispatch(
        window,
        awt = { TaskbarProgress.showIndeterminate(it) },
        tao = { TaoTaskbarProgress.showIndeterminate(it) },
    )

    fun showPaused(window: NucleusWindow, value: Double = 1.0): Boolean = dispatch(
        window,
        awt = { TaskbarProgress.showPaused(it, value) },
        tao = { TaoTaskbarProgress.showPaused(it, value) },
    )

    fun hideProgress(window: NucleusWindow): Boolean = dispatch(
        window,
        awt = { TaskbarProgress.hideProgress(it) },
        tao = { TaoTaskbarProgress.hideProgress(it) },
    )

    fun requestAttention(
        window: NucleusWindow,
        type: TaskbarProgress.AttentionType = TaskbarProgress.AttentionType.INFORMATIONAL,
    ): Boolean = dispatch(
        window,
        awt = { TaskbarProgress.requestAttention(it, type) },
        tao = { TaoTaskbarProgress.requestAttention(it, type) },
    )

    fun stopAttention(window: NucleusWindow): Boolean = dispatch(
        window,
        awt = { TaskbarProgress.stopAttention(it) },
        tao = { TaoTaskbarProgress.stopAttention(it) },
    )

    private inline fun dispatch(
        window: NucleusWindow,
        crossinline awt: (java.awt.Window) -> Boolean,
        crossinline tao: (io.github.kdroidfilter.nucleus.window.tao.TaoWindow) -> Boolean,
    ): Boolean {
        val awtWindow = window.unsafe.awtWindow
        val taoWindow = window.unsafe.taoWindow
        if (awtWindow == null && taoWindow == null) return false
        worker.submit {
            runCatching {
                if (awtWindow != null) awt(awtWindow) else tao(taoWindow!!)
            }
        }
        return true
    }
}

// Ergonomic extensions: `nucleusWindow.setTaskbarProgress(0.5)` reads naturally
// at the call site and removes the need to repeat the [NucleusTaskbarProgress]
// receiver.
fun NucleusWindow.setTaskbarProgress(value: Double): Boolean =
    NucleusTaskbarProgress.setProgress(this, value)

fun NucleusWindow.setTaskbarState(state: TaskbarProgress.State): Boolean =
    NucleusTaskbarProgress.setState(this, state)

fun NucleusWindow.showTaskbarProgress(value: Double): Boolean =
    NucleusTaskbarProgress.showProgress(this, value)

fun NucleusWindow.showTaskbarError(value: Double = 1.0): Boolean =
    NucleusTaskbarProgress.showError(this, value)

fun NucleusWindow.showTaskbarIndeterminate(): Boolean =
    NucleusTaskbarProgress.showIndeterminate(this)

fun NucleusWindow.showTaskbarPaused(value: Double = 1.0): Boolean =
    NucleusTaskbarProgress.showPaused(this, value)

fun NucleusWindow.hideTaskbarProgress(): Boolean =
    NucleusTaskbarProgress.hideProgress(this)

fun NucleusWindow.requestTaskbarAttention(
    type: TaskbarProgress.AttentionType = TaskbarProgress.AttentionType.INFORMATIONAL,
): Boolean = NucleusTaskbarProgress.requestAttention(this, type)

fun NucleusWindow.stopTaskbarAttention(): Boolean =
    NucleusTaskbarProgress.stopAttention(this)
