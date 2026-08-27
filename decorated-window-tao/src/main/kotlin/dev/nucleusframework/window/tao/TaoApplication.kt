package dev.nucleusframework.window.tao

import dev.nucleusframework.window.tao.dispatch.LifecycleMainDispatcherPriming
import dev.nucleusframework.window.tao.dispatch.TaoMainDispatcher
import dev.nucleusframework.window.tao.ffi.NativeTaoBridge
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
public object TaoApplication {
    private val handleSeq = AtomicLong(1L)
    private val windows = ConcurrentHashMap<Long, TaoWindow>()
    private var onLaunched: ((TaoApplication) -> Unit)? = null

    /**
     * Takes over the calling thread (must be the macOS main thread) and runs
     * the Tao event loop. Calls [block] once on launch with this object.
     */
    public fun run(block: (TaoApplication) -> Unit) {
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
    public fun exit() {
        NativeTaoBridge.nativeExit()
    }

    /**
     * Posts a window-creation request to the event loop. Returns immediately
     * with a [TaoWindow] handle; the OS window appears asynchronously.
     */
    public fun openWindow(
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
        // Full-window per-pixel transparency (#416). Creation-time only.
        transparent: Boolean = false,
        // Drop shadow on borderless windows (Windows DWM / macOS hasShadow).
        // Set false for overlays (`DecoratedWindow(undecorated)`).
        undecoratedShadow: Boolean = true,
        // Linux only: give this window an X11 surface even on a native Wayland
        // session, so it can use the window management Wayland has no protocol
        // for. Creation-time only. See [TaoWindow.isNativeWaylandSurface].
        forceX11: Boolean = false,
    ): TaoWindow {
        val handle = handleSeq.getAndIncrement()
        val window =
            TaoWindow(
                handle,
                isResizable = resizable,
                isPopup = popupOf != null,
                popupParentHandle = popupOf?.handle ?: 0L,
                requestedX11 = forceX11,
            )
        windows[handle] = window
        val safeWidth = if (width.isFinite() && width > 0.0) width else DEFAULT_WINDOW_WIDTH_DP
        val safeHeight = if (height.isFinite() && height > 0.0) height else DEFAULT_WINDOW_HEIGHT_DP
        NativeTaoBridge.nativeCreateWindow(
            handle,
            title,
            safeWidth,
            safeHeight,
            decorations,
            resizable,
            visible,
            maximized,
            popupOf?.handle ?: 0L,
            skipTaskbar,
            transparent,
            undecoratedShadow,
            forceX11,
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

        override fun onImeReplaceCommit(
            handle: Long,
            text: String,
        ) {
            lookup(handle)?.dispatchImeReplaceCommit(text)
        }

        override fun onImePreedit(
            handle: Long,
            text: String,
        ) {
            lookup(handle)?.dispatchImePreedit(text)
        }

        override fun onImeCommit(
            handle: Long,
            text: String,
        ) {
            lookup(handle)?.dispatchImeCommit(text)
        }
    }
}
