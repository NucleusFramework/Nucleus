package dev.nucleusframework.window.tao

import dev.nucleusframework.window.tao.dispatch.LifecycleMainDispatcherPriming
import dev.nucleusframework.window.tao.dispatch.TaoMainDispatcher
import dev.nucleusframework.window.tao.ffi.NativeTaoBridge
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.logging.Level
import java.util.logging.Logger

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
    private val logger = Logger.getLogger(TaoApplication::class.java.name)
    private val handleSeq = AtomicLong(1L)
    private val windows = ConcurrentHashMap<Long, TaoWindow>()
    private var onLaunched: ((TaoApplication) -> Unit)? = null

    /**
     * First unhandled [Throwable] that escaped an event dispatch on the Tao
     * main thread (first report wins). Recorded by [reportFatal]; [run] shows
     * the native error dialog and rethrows it after the loop exits so the
     * failure surfaces to the caller instead of vanishing into the JNI
     * boundary (Rust clears pending exceptions — issue #622).
     */
    private val fatalError = AtomicReference<Throwable?>(null)

    /**
     * Takes over the calling thread (must be the macOS main thread) and runs
     * the Tao event loop. Calls [block] once on launch with this object.
     *
     * If an exception escapes an event dispatch (including [block] itself),
     * the default fatal path runs — SEVERE log, native error dialog, clean
     * loop exit — and the throwable is then **rethrown from this function**
     * (#622). Callers that leave non-daemon threads alive (AWT/Swing) must
     * catch it and terminate the process themselves; [taoApplication] does.
     */
    public fun run(block: (TaoApplication) -> Unit) {
        check(NativeTaoBridge.isLoaded) {
            "nucleus_tao native library is not available — supported targets: " +
                "macOS (arm64/x86_64), Windows (x64/aarch64), Linux (x64/aarch64)."
        }
        // Fresh run: the native side supports re-running the loop, so a stale
        // fatal from a previous run must neither rethrow on a clean one nor
        // make a genuine new fatal take the log-only branch.
        fatalError.set(null)
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
        fatalError.get()?.let { t ->
            // The loop has exited (reportFatal posted the exit) and every tao
            // callback frame is unwound — only now is it safe to block in the
            // app-modal native dialog (a modal pump inside a tao callback
            // re-enters tao's non-reentrant handler mutex on Dock-reopen /
            // deep-link events and deadlocks the main thread). Then surface
            // the failure to the caller like any uncaught exception.
            showNativeErrorDialog(
                title = "Fatal Error",
                message = "The application encountered an unrecoverable error and will close.\n\n$t",
            )
            throw t
        }
    }

    /**
     * Default fatal-exception path (#622): an exception that escapes an event
     * dispatch would otherwise be cleared at the JNI boundary, leaving a
     * frozen, silent, unclosable window. Records [t] and posts a loop exit;
     * [run] then shows the native error dialog and rethrows. First report
     * wins — later ones (including any thrown during the shutdown the first
     * triggered) are only logged. [exit] is posted *before* logging so the
     * loop unwinds even if log formatting itself throws (e.g. an
     * OutOfMemoryError materializing the stack trace). Never freeze.
     *
     * Internal so the Compose layer (coroutine exception handler, render
     * loop) routes its own unrecoverable failures through the same path.
     */
    internal fun reportFatal(t: Throwable) {
        if (!fatalError.compareAndSet(null, t)) {
            logger.log(Level.SEVERE, "Unhandled exception on the Tao main thread while shutting down", t)
            return
        }
        exit()
        logger.log(Level.SEVERE, "Unhandled exception on the Tao main thread — closing", t)
    }

    /** `true` when [t] is the recorded fatal error — already logged and shown. */
    internal fun isReportedFatal(t: Throwable): Boolean = fatalError.get() === t

    /** Runs [block], routing any escaped [Throwable] to [reportFatal]. */
    @Suppress("TooGenericExceptionCaught")
    private inline fun guarded(block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            reportFatal(t)
        }
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
            guarded {
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
        }

        override fun onKeyEvent(
            handle: Long,
            type: Int,
            vkCode: Int,
            keyLocation: Int,
            modifiers: Int,
            codePoint: Int,
        ) {
            guarded { lookup(handle)?.dispatchKey(type, vkCode, keyLocation, modifiers, codePoint) }
        }

        override fun onTrackpadGesture(
            handle: Long,
            kind: Int,
            phase: Int,
            xFixed: Int,
            yFixed: Int,
            valueFixed: Int,
        ) {
            guarded { lookup(handle)?.dispatchTrackpadGesture(kind, phase, xFixed, yFixed, valueFixed) }
        }

        override fun onTouchInput(
            handle: Long,
            phase: Int,
            id: Long,
            xFixed: Int,
            yFixed: Int,
            forceFixed: Int,
        ) {
            guarded { lookup(handle)?.dispatchTouchInput(phase, id, xFixed, yFixed, forceFixed) }
        }

        override fun onImeReplaceCommit(
            handle: Long,
            text: String,
            replacementStart: Long,
            replacementLength: Long,
        ) {
            guarded { lookup(handle)?.dispatchImeReplaceCommit(text, replacementStart, replacementLength) }
        }

        override fun onImePreedit(
            handle: Long,
            text: String,
        ) {
            guarded { lookup(handle)?.dispatchImePreedit(text) }
        }

        override fun onImeCommit(
            handle: Long,
            text: String,
        ) {
            guarded { lookup(handle)?.dispatchImeCommit(text) }
        }
    }
}

/**
 * Shows the blocking, app-modal native fatal-error dialog when the Tao
 * native library is available; no-ops otherwise (the SEVERE log is the
 * fallback). Never throws — this runs on the fatal path, where a secondary
 * failure must not mask the clean shutdown. macOS and Windows; Linux still
 * no-ops inside the native library (#622).
 */
@Suppress("TooGenericExceptionCaught")
internal fun showNativeErrorDialog(
    title: String,
    message: String,
) {
    // `nucleus.tao.fatalErrorDialog=false` is the escape hatch for unattended
    // runs (CI, AOT training): a blocking modal nobody can dismiss would hang
    // the process there; the SEVERE log is the signal instead.
    if (!NativeTaoBridge.isLoaded ||
        !System.getProperty("nucleus.tao.fatalErrorDialog", "true").toBoolean()
    ) {
        return
    }
    try {
        NativeTaoBridge.nativeShowErrorDialog(title, message)
    } catch (t: Throwable) {
        Logger
            .getLogger(TaoApplication::class.java.name)
            .log(Level.WARNING, "Native error dialog failed", t)
    }
}
