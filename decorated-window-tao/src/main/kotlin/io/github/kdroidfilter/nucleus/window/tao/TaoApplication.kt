package io.github.kdroidfilter.nucleus.window.tao

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Phase 1 entry point for the Tao backend.
 *
 * Usage from a GraalVM native-image `main()`:
 *
 * ```kotlin
 * fun main() {
 *     TaoApplication.run { app ->
 *         val win = app.openWindow(title = "Hello Tao", width = 640.0, height = 480.0)
 *         win.onCloseRequested { app.exit() }
 *     }
 * }
 * ```
 *
 * The lambda runs once Tao has finished launching, on the macOS main thread.
 * [run] does **not** return until [TaoApplication.exit] is called.
 */
object TaoApplication {
    private val handleSeq = AtomicLong(1L)
    private val windows = ConcurrentHashMap<Long, TaoWindow>()
    private var onLaunched: ((TaoApplication) -> Unit)? = null

    /**
     * Takes over the calling thread (must be the macOS main thread) and runs
     * the Tao event loop. Calls [block] once on launch with this object.
     */
    fun run(block: (TaoApplication) -> Unit) {
        check(NativeTaoBridge.isLoaded) {
            "nucleus_tao native library is not available — did you run on macOS arm64/x86_64?"
        }
        onLaunched = block
        NativeTaoBridge.nativeRunBlocking(EventDispatcher)
    }

    /** Posts an exit request and unblocks [run]. */
    fun exit() {
        NativeTaoBridge.nativeExit()
    }

    /**
     * Posts a window-creation request to the event loop. Returns immediately
     * with a [TaoWindow] handle; the OS window appears asynchronously.
     */
    fun openWindow(
        title: String = "Window",
        width: Double = 640.0,
        height: Double = 480.0,
        decorations: Boolean = true,
        resizable: Boolean = true,
        visible: Boolean = true,
    ): TaoWindow {
        val handle = handleSeq.getAndIncrement()
        val window = TaoWindow(handle)
        windows[handle] = window
        NativeTaoBridge.nativeCreateWindow(handle, title, width, height, decorations, resizable, visible)
        return window
    }

    internal fun lookup(handle: Long): TaoWindow? = windows[handle]

    internal fun remove(handle: Long) {
        windows.remove(handle)
    }

    private object EventDispatcher : NativeTaoBridge.EventCallback {
        override fun onEvent(
            handle: Long,
            code: Int,
            a: Int,
            b: Int,
        ) {
            when (code) {
                TaoEventCode.LAUNCHED -> {
                    val cb = onLaunched
                    onLaunched = null
                    cb?.invoke(this@TaoApplication)
                }
                else -> lookup(handle)?.dispatch(code, a, b)
            }
        }

        override fun onKeyEvent(
            handle: Long,
            type: Int,
            vkCode: Int,
            keyLocation: Int,
            modifiers: Int,
            codePoint: Int,
        ) {
            lookup(handle)?.dispatchKey(type, vkCode, keyLocation, modifiers, codePoint)
        }
    }
}
