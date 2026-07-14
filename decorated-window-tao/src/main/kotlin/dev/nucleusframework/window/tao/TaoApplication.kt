package dev.nucleusframework.window.tao

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
            "nucleus_tao native library is not available — supported targets: " +
                "macOS (arm64/x86_64), Windows (x64/aarch64), Linux (x64/aarch64)."
        }
        // Capture the Tao main thread eagerly, before the native event loop
        // takes over this thread. Required so `Dispatchers.Main` consumers
        // (notably AndroidX Lifecycle's synchronous `MainDispatcherChecker`)
        // can resolve the Tao thread immediately — a lazy capture at first
        // pump would race the very first `NavHost.setGraph` → `addObserver`
        // call on real apps.
        TaoMainDispatcher.taoMainThread = Thread.currentThread()
        // Hand queue draining over to the native loop: from here `dispatch`
        // wakes Tao and `pump()` drains `pending`, instead of the pre-loop
        // fallback thread (see TaoMainDispatcher, issue #337). Done *before*
        // Lifecycle priming so the fallback is fully quiesced and cannot
        // re-poison `MainDispatcherChecker` after we prime it below.
        TaoMainDispatcher.onNativeLoopStarting()
        // Pre-seed Lifecycle's MainDispatcherChecker so its lazy
        // `runBlocking(Dispatchers.Main.immediate)` probe never fires from
        // inside the pump — on Lifecycle 2.10.x that probe deadlocks the
        // first `NavController.setGraph` call.
        LifecycleMainDispatcherPriming.primeWithCurrentThread()
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
        maximized: Boolean = false,
        // Linux only: make this window a popup overlay of [popupOf]
        // (GTK_WINDOW_POPUP transient → wl_subsurface on Wayland, the only
        // client-positionable window kind under xdg-shell). For
        // cursor-following overlays such as drag ghosts. Ignored elsewhere.
        popupOf: TaoWindow? = null,
        // Windows: keep the window off the taskbar and Alt+Tab. Linux: GTK
        // skip-taskbar hint (X11/XWayland only). Must be set at creation
        // (tao builder attribute); see NativeTaoBridge.
        skipTaskbar: Boolean = false,
    ): TaoWindow {
        val handle = handleSeq.getAndIncrement()
        val window = TaoWindow(handle, isResizable = resizable, isPopup = popupOf != null)
        windows[handle] = window
        NativeTaoBridge.nativeCreateWindow(
            handle,
            title,
            width,
            height,
            decorations,
            resizable,
            visible,
            maximized,
            popupOf?.handle ?: 0L,
            skipTaskbar,
        )
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
                TaoEventCode.MAIN_EVENTS_CLEARED -> TaoMainDispatcher.pump()
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

        override fun onTrackpadGesture(
            handle: Long,
            kind: Int,
            phase: Int,
            xFixed: Int,
            yFixed: Int,
            valueFixed: Int,
        ) {
            lookup(handle)?.dispatchTrackpadGesture(kind, phase, xFixed, yFixed, valueFixed)
        }

        override fun onTouchInput(
            handle: Long,
            phase: Int,
            id: Long,
            xFixed: Int,
            yFixed: Int,
            forceFixed: Int,
        ) {
            lookup(handle)?.dispatchTouchInput(phase, id, xFixed, yFixed, forceFixed)
        }
    }
}
