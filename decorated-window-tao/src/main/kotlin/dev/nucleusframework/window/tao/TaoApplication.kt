package dev.nucleusframework.window.tao

import dev.nucleusframework.core.runtime.NucleusUiThread
import dev.nucleusframework.core.runtime.WindowBackend
import dev.nucleusframework.window.tao.dispatch.LifecycleMainDispatcherPriming
import dev.nucleusframework.window.tao.dispatch.TaoMainDispatcher
import dev.nucleusframework.window.tao.ffi.NativeTaoBridge
import kotlinx.coroutines.CoroutineExceptionHandler
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.logging.Level
import java.util.logging.Logger
import kotlin.coroutines.EmptyCoroutineContext

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
@Suppress("TooManyFunctions")
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

    /** Ensures the native dialog is shown at most once per recorded fatal. */
    private val fatalDialogShown = AtomicBoolean(false)

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
        fatalDialogShown.set(false)
        resetQuit()
        // Capture the Tao main thread eagerly, before the native event loop
        // takes over this thread. Required so `Dispatchers.Main` consumers
        // (notably AndroidX Lifecycle's synchronous `MainDispatcherChecker`)
        // can resolve the Tao thread immediately — a lazy capture at first
        // pump would race the very first `NavHost.setGraph` → `addObserver`
        // call on real apps.
        TaoMainDispatcher.taoMainThread = Thread.currentThread()
        // Record the backend for libraries that branch on it without depending
        // on Compose or Tao. `nucleusApplication` sets it earlier in its own
        // bootstrap (before the loop exists); setting it again here is
        // idempotent and covers a bare `TaoApplication.run` app, which would
        // otherwise keep reporting the `Awt` fallback.
        WindowBackend.setActive(WindowBackend.Tao)
        // Route native integrations (notifications, launchers, media keys, …)
        // to this thread instead of the AWT EDT, which is not Compose's UI
        // thread under Tao (issue #310). Registered here rather than in
        // `nucleusApplication` so a bare `TaoApplication.run` app gets it too.
        NucleusUiThread.setExecutor(
            Executor { runnable -> TaoMainDispatcher.dispatch(EmptyCoroutineContext, runnable) },
        )
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
        // Watch the loop from the outside (#643): a stall deadlocks this
        // thread, so nothing downstream of `nativeRunBlocking` — including
        // `rethrowPendingFatal` below — can ever report it.
        TaoEventLoopWatchdog.start()
        try {
            NativeTaoBridge.nativeRunBlocking(EventDispatcher)
        } finally {
            TaoEventLoopWatchdog.stop()
        }
        // The loop has exited (reportFatal posted the exit) and every tao
        // callback frame is unwound — only now is it safe to block in the
        // app-modal native dialog (a modal pump inside a tao callback
        // re-enters tao's non-reentrant handler mutex on Dock-reopen /
        // deep-link events and deadlocks the main thread).
        rethrowPendingFatal()
    }

    /**
     * Shows the native error dialog (once) and rethrows the recorded fatal,
     * if any. [run] calls it right after the loop exits; [taoApplication]
     * calls it again just before finishing (exit or return) to catch a fatal
     * reported from a non-main thread (the coroutine exception handler runs
     * on the failing coroutine's thread) after [run]'s check already passed —
     * without the recheck such a crash would end the process with exit
     * code 0 as if the user had quit normally.
     */
    internal fun rethrowPendingFatal() {
        val t = fatalError.get() ?: return
        if (fatalDialogShown.compareAndSet(false, true)) {
            showNativeErrorDialog(
                title = "Fatal Error",
                message = "The application encountered an unrecoverable error and will close.",
                detail = t.stackTraceToString(),
            )
        }
        throw t
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
            // Re-post the exit: the first report's exit() silently no-ops when
            // it lands before the event-loop proxy is (re-)installed, and
            // first-report-wins must never leave the loop running with a
            // recorded fatal nobody will act on.
            exit()
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

    /**
     * `true` from the moment the OS asks the app to quit — macOS Cmd+Q, Dock →
     * Quit, logout / restart / shutdown — while the windows are being asked to
     * close, and for good once they all did. Reset when a window keeps itself
     * open, which cancels the quit. Electron's `before-quit` flag: an
     * `onCloseRequest` that normally hides to the tray checks it to let a real
     * quit through (`if (isQuitting) exitApplication() else hide()`).
     */
    @Volatile
    public var isQuitting: Boolean = false
        private set

    /** How a completed quit ends the app; the Compose loop routes it through `exitApplication`. */
    internal var quitExit: () -> Unit = ::exit

    /** Runs its argument once the close requests have taken effect; the Compose loop waits for recomposition. */
    internal var afterQuitRequests: (() -> Unit) -> Unit = { it() }

    /**
     * System quit (#696), Electron's `Browser::Quit`: every app window gets its
     * cancelable close request, newest first; the app exits once they all
     * closed, and a window that stays open cancels the quit. No app window →
     * exit at once. Repeated requests while one is in flight are ignored; a
     * window opened meanwhile defers the exit until it closes, and a new
     * request asks every window again.
     */
    internal fun requestQuit(open: Collection<TaoWindow> = windows.values) {
        if (quitInFlight) return
        quitScope = open
        waitingForLastWindow = false
        val targets = open.filter { it.closesOnQuit && !it.isClosing }.sortedByDescending { it.handle }
        isQuitting = true
        if (targets.isEmpty()) {
            quitExit()
            return
        }
        quitInFlight = true
        quitConsented.clear()
        targets.forEach { window ->
            askingWindow = window
            try {
                window.requestUserClose()
            } finally {
                askingWindow = null
            }
            if (quitConsent) quitConsented += window
            quitConsent = false
        }
        afterQuitRequests {
            quitInFlight = false
            when {
                targets.any { !it.isClosing && it !in quitConsented } -> isQuitting = false
                // A window opened meanwhile (a "Save?" dialog) keeps the app alive;
                // the quit completes once it is gone — Electron's OnWindowAllClosed.
                openAppWindows().isEmpty() -> quitExit()
                else -> waitingForLastWindow = true
            }
        }
    }

    /** App windows still open that have not agreed to the quit in flight. */
    private fun openAppWindows(): List<TaoWindow> =
        quitScope.filter { it.closesOnQuit && !it.isClosing && it !in quitConsented }

    /** Called as a window goes away: completes a quit that was waiting for the last one. */
    private fun completeQuitIfLastWindow() {
        if (waitingForLastWindow && openAppWindows().isEmpty()) {
            waitingForLastWindow = false
            quitExit()
        }
    }

    private var quitInFlight = false
    private var waitingForLastWindow = false
    private var quitScope: Collection<TaoWindow> = emptyList()
    private val quitConsented = HashSet<TaoWindow>()

    /** The window whose close request [requestQuit] is running, or `null`. */
    private var askingWindow: TaoWindow? = null
    private var quitConsent = false

    /**
     * `exitApplication()` called from a window's close request during a quit
     * is that window's *consent* (Electron's `app.quit()` while quitting), not
     * an exit that would override another window's veto: `true` when the call
     * was absorbed that way.
     */
    internal fun consentToQuit(): Boolean {
        if (askingWindow == null) return false
        quitConsent = true
        return true
    }

    /** Fresh-run quit state; [run] starts with it, tests reset through it. */
    internal fun resetQuit() {
        isQuitting = false
        quitInFlight = false
        waitingForLastWindow = false
        quitScope = emptyList()
        quitConsented.clear()
        askingWindow = null
        quitConsent = false
        quitExit = ::exit
        afterQuitRequests = { it() }
    }

    /**
     * Handlers for [onUnresponsive] / [onResponsive]. One each, replaced on
     * registration rather than appended — `nucleusApplication`'s block is
     * `@Composable`, so an appending registry would grow by one copy per
     * recomposition and fire the app's crash reporter N times for one stall.
     * `onDeepLink` has the same replace semantics for the same reason.
     * Volatile: written on the loop thread, read from the watchdog thread.
     */
    @Volatile
    private var unresponsiveHandler: (() -> Unit)? = null

    @Volatile
    private var responsiveHandler: (() -> Unit)? = null

    /**
     * Registers [listener] for "the UI stopped responding", Electron's
     * `webContents` `unresponsive` event (#643). Fires once per stall, after
     * the OS has flagged the window and the watchdog's grace period on top of
     * it; [onResponsive] closes the episode.
     *
     * One handler at a time: a second call replaces the first, like
     * [onDeepLink]'s sink. That is what makes it safe to call straight from
     * the `@Composable` application block, which recomposes.
     *
     * Nucleus itself only logs `SEVERE` with a thread dump — like Chromium's
     * HangWatcher or IntelliJ's PerformanceWatcher, and like Electron it ships
     * no built-in UI. What to do with the event is the app's call: report it
     * to a crash backend, or offer the user the browsers' "wait or quit"
     * choice.
     *
     * **[listener] runs on `nucleus-tao-watchdog-events`, not the UI thread**
     * — the UI thread is the one that is stuck, so anything posted to it
     * (Compose state, `Dispatchers.Main`) would only run once the stall is
     * over, if ever. That thread is the callbacks' own: it is neither the
     * sampling thread nor the UI thread, so a listener that blocks — a "wait
     * or quit" prompt is the expected use — delays only the next callback,
     * never the detection. Callbacks are serialized in order. A throwing
     * listener is logged and ignored: the watchdog must survive it.
     */
    public fun onUnresponsive(listener: () -> Unit) {
        unresponsiveHandler = listener
    }

    /**
     * Registers [listener] for "the UI is responding again", Electron's
     * `responsive` event — the counterpart of [onUnresponsive], fired only
     * after a stall that was reported. Same threading and replace semantics.
     */
    public fun onResponsive(listener: () -> Unit) {
        responsiveHandler = listener
    }

    /**
     * Runs [block] with the hang watchdog told that a stall is *expected*
     * (#643) — Chromium's `HangWatcher::InvalidateActiveExpectations()`.
     *
     * The watchdog reports any UI thread that stops pumping, which includes an
     * operation the app knows is long and synchronous. Wrap that operation and
     * neither the `SEVERE` report nor [onUnresponsive] fires for it; everything
     * else stays watched, unlike the `nucleus.tao.watchdog=false` switch, which
     * gives up on the whole process.
     *
     * ```kotlin
     * expectUnresponsive { importHugeProjectSynchronously() }
     * ```
     *
     * Reentrant, and thread-safe: the scope is the app's, not one thread's. A
     * stall already reported when the scope opens still gets its
     * [onResponsive], so the two events stay paired.
     *
     * Prefer moving the work off the UI thread. This is for the cases where
     * that is not an option — a native call that must run on the loop, a
     * shutdown flush — not a way to make a slow UI quiet.
     */
    public fun <T> expectUnresponsive(block: () -> T): T {
        TaoEventLoopWatchdog.beginExpectedStall()
        try {
            return block()
        } finally {
            TaoEventLoopWatchdog.endExpectedStall()
        }
    }

    /** Fires the [onUnresponsive] handler; called by the watchdog thread. */
    internal fun notifyUnresponsive(): Unit = notify(unresponsiveHandler, "unresponsive")

    /** Fires the [onResponsive] handler; called by the watchdog thread. */
    internal fun notifyResponsive(): Unit = notify(responsiveHandler, "responsive")

    @Suppress("TooGenericExceptionCaught")
    private fun notify(
        handler: (() -> Unit)?,
        event: String,
    ) {
        try {
            handler?.invoke()
        } catch (t: Throwable) {
            logger.log(Level.SEVERE, "Unhandled exception in the '$event' handler", t)
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

    /** Live native windows, by handle. Used by tests to catch leaked windows. */
    internal fun liveWindowCount(): Int = windows.size

    internal fun remove(handle: Long) {
        windows.remove(handle)
        completeQuitIfLastWindow()
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
                    TaoEventCode.QUIT_REQUESTED -> requestQuit()
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

        override fun onScrollGesture(
            handle: Long,
            phase: Int,
            dxFixed: Int,
            dyFixed: Int,
        ) {
            guarded { lookup(handle)?.dispatchScrollGesture(phase, dxFixed, dyFixed) }
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
 * Routes unhandled coroutine failures to [TaoApplication.reportFatal] (#622).
 * Installed by the scene-bundle factories (`TaoSceneBundle.kt` — every scene,
 * including popup layers and standalone popups, funnels through them, with a
 * teardown filter), the macOS render loop and the [taoApplication] scope, so
 * composition-side crashes (LaunchedEffect, recomposition, the render frame)
 * take the fatal path — without a handler they die in kotlinx's global
 * handler (stderr only) and the window silently stops recomposing.
 */
internal val TaoFatalCoroutineExceptionHandler: CoroutineExceptionHandler =
    CoroutineExceptionHandler { _, t -> TaoApplication.reportFatal(t) }

/**
 * Log-only counterpart of [TaoFatalCoroutineExceptionHandler] for scopes
 * whose children are deliberately isolated (`SupervisorJob` gesture helpers):
 * a crash there costs one gesture, not the app. SEVERE keeps it loud without
 * escalating.
 */
internal val TaoNonFatalCoroutineExceptionHandler: CoroutineExceptionHandler =
    CoroutineExceptionHandler { _, t ->
        Logger
            .getLogger(TaoApplication::class.java.name)
            .log(Level.SEVERE, "Unhandled exception in an isolated Tao coroutine", t)
    }

/**
 * Shows the blocking, app-modal native fatal-error dialog when the Tao
 * native library is available; no-ops otherwise (the SEVERE log is the
 * fallback). Never throws — this runs on the fatal path, where a secondary
 * failure must not mask the clean shutdown. macOS, Windows and Linux (#622).
 * [detail] carries the full stack trace — see
 * [NativeTaoBridge.nativeShowErrorDialog] for the per-platform rendering.
 */
@Suppress("TooGenericExceptionCaught")
internal fun showNativeErrorDialog(
    title: String,
    message: String,
    detail: String,
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
        NativeTaoBridge.nativeShowErrorDialog(title, message, detail)
    } catch (t: Throwable) {
        Logger
            .getLogger(TaoApplication::class.java.name)
            .log(Level.WARNING, "Native error dialog failed", t)
    }
}
