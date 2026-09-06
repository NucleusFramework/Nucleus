package dev.nucleusframework.window.tao.ffi

import dev.nucleusframework.core.runtime.NativeLibraryLoader
import dev.nucleusframework.window.tao.TaoAccessibilityRegistry
import dev.nucleusframework.window.tao.TaoApplication
import dev.nucleusframework.window.tao.TaoDeepLinkBridge
import java.util.logging.Level
import java.util.logging.Logger

private const val LIBRARY_NAME = "nucleus_tao"

/**
 * Direct JNI bridge over the Tao windowing library.
 *
 * Cross-platform: macOS, Windows and Linux (X11 + Wayland via GTK). On macOS
 * the event loop owned by [nativeRunBlocking] must run on the OS main thread
 * (process thread 0); GraalVM native-image guarantees this, on a regular JVM
 * launch with `-XstartOnFirstThread`. Windows and Linux have no such
 * constraint — Tao installs its message pump / GTK main loop on whichever
 * thread calls `nativeRunBlocking`.
 */
@Suppress("TooManyFunctions")
internal object NativeTaoBridge {
    private val logger = Logger.getLogger(NativeTaoBridge::class.java.name)
    private val loaded = NativeLibraryLoader.load(LIBRARY_NAME, NativeTaoBridge::class.java)

    val isLoaded: Boolean get() = loaded

    /**
     * Guard for native → JVM upcalls that run framework plumbing only (deep
     * links). An exception escaping into JNI is cleared silently by the Rust
     * side (#622) — log it at SEVERE instead. One-shot handlers, not the
     * render/dispatch path, so a failure is loud but non-fatal.
     */
    @Suppress("TooGenericExceptionCaught")
    private inline fun upcall(block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            logger.log(Level.SEVERE, "Unhandled exception in a native → JVM upcall", t)
        }
    }

    /**
     * Guard for native → JVM upcalls that run **app code** — a11y actions
     * invoke the same semantics lambdas (e.g. `Modifier.clickable` onClick)
     * that are fatal when reached via mouse or keyboard (#622). A crash must
     * behave identically regardless of input modality, so these route to
     * [TaoApplication.reportFatal] instead of a log-only swallow that would
     * leave screen-reader users with an app whose state desynced mid-action.
     */
    @Suppress("TooGenericExceptionCaught")
    private inline fun fatalUpcall(block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            TaoApplication.reportFatal(t)
        }
    }

    /**
     * Receives events dispatched from the Rust event loop, called on the
     * macOS main thread. [code] matches the constants in [TaoEventCode];
     * [a]/[b] carry packed payloads (e.g. width/height, button, scancode).
     */
    interface EventCallback {
        @Suppress("FunctionParameterNaming")
        fun onEvent(
            handle: Long,
            code: Int,
            a: Int,
            b: Int,
        )

        /**
         * Keyboard event callback (separate from [onEvent] because it carries
         * 5 payload values). [type] is [TaoEventCode.KEY_DOWN] or [KEY_UP].
         * [vkCode]/[keyLocation] follow AWT's `KeyEvent.VK_*` / `KEY_LOCATION_*`
         * conventions so Compose's `Key(nativeKeyCode, nativeKeyLocation)`
         * works unchanged. [modifiers] is a bitmask: 1=Shift, 2=Ctrl, 4=Alt,
         * 8=Meta. [codePoint] is the UTF-32 code-point produced by the key, or 0.
         */
        @Suppress("LongParameterList", "FunctionParameterNaming")
        fun onKeyEvent(
            handle: Long,
            type: Int,
            vkCode: Int,
            keyLocation: Int,
            modifiers: Int,
            codePoint: Int,
        )

        /**
         * macOS-only trackpad gesture callback (pinch / rotate / smart-magnify).
         * Tao does not expose these natively, so they are intercepted via an
         * NSEvent local monitor in `macos/touchpad_gestures.m` and forwarded
         * here through Rust's `dispatch_trackpad_gesture` helper.
         *
         * [kind] is [TaoTrackpadGesture.MAGNIFY] / [ROTATE] / [SMART_MAGNIFY].
         * [phase] is [TaoTrackpadPhase.BEGAN] / [CHANGED] / [ENDED] / [CANCELLED]
         * (smart-magnify is a one-shot reported as [CHANGED]).
         * [xFixed]/[yFixed] are physical pixels × 1024 (matches CursorMoved).
         * [valueFixed] is the per-event delta × 10 000 — magnification ratio
         * for [MAGNIFY], degrees for [ROTATE], 0 for [SMART_MAGNIFY].
         *
         * Default implementation no-ops so non-macOS callers can ignore it.
         */
        @Suppress("LongParameterList", "FunctionParameterNaming")
        fun onTrackpadGesture(
            handle: Long,
            kind: Int,
            phase: Int,
            xFixed: Int,
            yFixed: Int,
            valueFixed: Int,
        ) {
        }

        /**
         * Windows touchscreen input. Tao emits one `WindowEvent::Touch` per
         * finger update (WM_POINTER / WM_TOUCH), forwarded here verbatim.
         * The JVM side aggregates the active set before issuing
         * `ComposeScene.sendPointerEvent`.
         *
         * [phase] is one of [TaoTouchEvent.PRESS] / [MOVE] / [RELEASE] / [CANCEL].
         * [id] is the OS-assigned finger id (reusable after [RELEASE]).
         * [xFixed]/[yFixed] are physical pixels × 1024 (matches
         * [TaoEventCode.CURSOR_MOVED]).
         * [forceFixed] is the touch pressure × 10 000 in `[0, 10000]`, or
         * `-1` when the digitizer doesn't report pressure.
         *
         * Default no-op so non-Windows callers can ignore it.
         */
        @Suppress("LongParameterList", "FunctionParameterNaming")
        fun onTouchInput(
            handle: Long,
            phase: Int,
            id: Long,
            xFixed: Int,
            yFixed: Int,
            forceFixed: Int,
        ) {
        }

        /**
         * macOS replacement commit: `insertText:` with a valid
         * `replacementRange` outside a composition — how the press-and-hold
         * accent picker replaces the base letter on a document-backed client
         * (#611/#612). [replacementStart] / [replacementLength] are UTF-16
         * offsets in the same document-absolute space the host pushed
         * through [nativeSetImeDocument]; the host replaces that range via
         * Compose `TextEditingScope` (select-then-insert, Chromium's
         * `ImeCommitText` semantics). Default no-op.
         */
        fun onImeReplaceCommit(
            handle: Long,
            text: String,
            replacementStart: Long,
            replacementLength: Long,
        ) {
        }

        /**
         * macOS IME composition update (`setMarkedText:` / `unmarkText`).
         * [text] is the current marked text — empty when the composition
         * was cancelled. Default no-op. See issue #595.
         */
        fun onImePreedit(
            handle: Long,
            text: String,
        ) {
        }

        /**
         * macOS IME composition commit (`insertText:` while marked text is
         * active). [text] replaces the composing region via
         * `TextEditingScope.commitText`. Default no-op. See issue #595.
         */
        fun onImeCommit(
            handle: Long,
            text: String,
        ) {
        }
    }

    /** Takes over the calling thread. Blocks until [nativeExit] is called. */
    @JvmStatic
    external fun nativeRunBlocking(callback: EventCallback)

    @JvmStatic
    external fun nativeCreateWindow(
        handle: Long,
        title: String,
        width: Double,
        height: Double,
        decorations: Boolean,
        resizable: Boolean,
        visible: Boolean,
        maximized: Boolean,
        // Linux only: non-zero = handle of the window this one is a popup
        // overlay of (GTK_WINDOW_POPUP transient; wl_subsurface on Wayland —
        // the only client-positionable window kind under xdg-shell). Ignored
        // on other platforms.
        popupOf: Long,
        // Windows: keep the window off the taskbar and Alt+Tab
        // (WS_EX_TOOLWINDOW via tao's WindowFlags). Builder-time attribute:
        // tao rewrites GWL_EXSTYLE from its flags on every state change, so a
        // post-creation style change does not survive activation.
        // Linux: GTK skip-taskbar/skip-pager hints — effective on X11 and
        // XWayland, ignored on native Wayland (no taskbar opt-out protocol).
        // Ignored on macOS (Dock hiding uses the activation policy).
        skipTaskbar: Boolean,
        // Full-window per-pixel transparency (#416). Creation-time only —
        // maps to tao `with_transparent`. Pair with an alpha-0 clear
        // (`WindowBackground(Color.Transparent)` or the transparent=true
        // default style path) so the desktop shows through empty regions.
        transparent: Boolean,
        // Drop shadow for borderless windows. Windows: DWM undecorated shadow
        // (outer-rect inset). macOS: NSWindow.hasShadow. Ghost overlays pass
        // false (`DecoratedWindow(undecorated = true)`). Ignored on Linux
        // (CSD shadow is gated in the host).
        undecoratedShadow: Boolean,
        // Linux: give this window an X11 surface even when the process runs on
        // native Wayland, by re-homing it on a second GdkDisplay opened on
        // DISPLAY. Creation-time only. Ignored elsewhere and when the process
        // is already an X11/XWayland client. Silently keeps the Wayland surface
        // when no X server is reachable — callers check the surface kind
        // (`nativeLinuxHandles`) rather than a return value.
        forceX11: Boolean,
    )

    @JvmStatic
    external fun nativeSetVisible(
        handle: Long,
        visible: Boolean,
    )

    @JvmStatic
    external fun nativeSetTitle(
        handle: Long,
        title: String,
    )

    @JvmStatic
    external fun nativeRequestRedraw(handle: Long)

    @JvmStatic
    external fun nativeRequestClose(handle: Long)

    @JvmStatic
    external fun nativeExit()

    /**
     * Shows a blocking native error dialog — the no-AWT replacement for
     * Compose Desktop's Swing default (#622). macOS (NSAlert run by an
     * out-of-process osascript child — our own NSApp is unusable after the
     * Tao loop; falls back to a compact CFUserNotificationDisplayAlert when
     * the child cannot run), Windows (in-memory DLGTEMPLATE dialog run on a
     * fresh thread; falls back to a compact MessageBoxW when the dialog
     * cannot be created) and Linux (modal GtkMessageDialog —
     * GTK-main-thread only, i.e. the thread that ran the Tao loop).
     * [detail] is the full stack trace: all three platforms render it in a
     * scrollable monospace view with a Copy button; the macOS and Windows
     * fallbacks keep the compact alert and show only its first
     * `toString()` line after [message].
     * Call it only outside tao callback frames — a modal pump inside one
     * re-enters tao's non-reentrant handler mutex.
     */
    @JvmStatic
    external fun nativeShowErrorDialog(
        title: String,
        message: String,
        detail: String,
    )

    /**
     * Wakes the Tao event loop so a coroutine just posted to
     * [TaoMainDispatcher] runs on the next tick. Required because Tao runs
     * with `ControlFlow::Wait` and would otherwise sleep until an OS event
     * arrives — leaving the dispatcher queue undrained when no window is
     * open (e.g. during early startup or after [exitApplication]).
     */
    @JvmStatic
    external fun nativeWake()

    @JvmStatic
    external fun nativeIsAvailable(): Boolean

    @JvmStatic
    @Suppress("unused") // called from JNI (macOS Event::Opened → apple_events::dispatch_deep_link)
    fun dispatchDeepLink(uri: String) {
        upcall { TaoDeepLinkBridge.onUrlFromNative(uri) }
    }

    /**
     * Returns the underlying NSView pointer for the given window handle. Must
     * be called on the macOS main thread. Returns 0 if the window does not
     * exist (yet) or has been closed. Only resolvable on macOS — calling on
     * other platforms throws `UnsatisfiedLinkError`.
     *
     * Prefer [nativeNsWindowHandle] / [TaoWindow.nsWindowHandle] when the goal
     * is dialog parenting (sheets) rather than rendering into the view.
     */
    @JvmStatic
    external fun nativeNsViewHandle(handle: Long): Long

    /**
     * macOS only: returns the owning `NSWindow*` for [handle] (cast to
     * `Long`), or 0 if the window is unknown / not yet realized.
     *
     * Intended for native dialog parenting (`beginSheetModalForWindow:`,
     * future FileKit `FileKitDialogParent.macos`). Distinct from
     * [nativeNsViewHandle] — an NSView is not a valid sheet parent.
     */
    @JvmStatic
    external fun nativeNsWindowHandle(handle: Long): Long

    /**
     * macOS only, headful e2e: present a real `NSOpenPanel` as a sheet on
     * [nsWindow], confirm attachment (and that [nsView] is in that window's
     * hierarchy), then cancel. Return codes:
     * `1` ok, `0` window not found, `-1` view not in hierarchy,
     * `-2` sheet did not attach, `-3` sheet did not dismiss cleanly.
     */
    @JvmStatic
    external fun nativeMacOsProbeSheetParent(
        nsWindow: Long,
        nsView: Long,
    ): Int

    /**
     * macOS only, headful e2e: `true` when Japanese Kotoeri (romaji/hiragana)
     * is installed and can be selected — even if it is currently disabled in
     * the input-source menu.
     */
    @JvmStatic
    external fun nativeMacOsKotoeriAvailable(): Boolean

    /**
     * macOS only, headful e2e: enable Kotoeri if needed, select Hiragana,
     * make [handle]'s view first responder and activate its input context.
     * Saves the previous input source for [nativeMacOsKotoeriRestore].
     */
    @JvmStatic
    external fun nativeMacOsKotoeriSelect(handle: Long): Boolean

    /**
     * macOS only, headful e2e: restore the input source saved by
     * [nativeMacOsKotoeriSelect] and disable Kotoeri again if this process
     * enabled it. No-op when select was never called.
     */
    @JvmStatic
    external fun nativeMacOsKotoeriRestore()

    /** macOS only, headful e2e: current TIS keyboard input source id. */
    @JvmStatic
    external fun nativeMacOsCurrentInputSource(): String

    /**
     * macOS only, headful e2e: deliver a real AppKit `keyDown:` / `keyUp:`
     * to TaoView for [handle]. [keyCode] is a Carbon virtual key
     * (`kVK_ANSI_*`). This is the same path a physical keystroke takes, so
     * Kotoeri's `interpretKeyEvents:` → `setMarkedText:` / `insertText:`
     * runs for real. [autorepeat] marks the event as a key repeat (held
     * key) — what AppKit's press-and-hold machinery engages on.
     */
    @JvmStatic
    external fun nativeMacOsPostKeyToView(
        handle: Long,
        keyCode: Int,
        characters: String,
        down: Boolean,
        autorepeat: Boolean,
    ): Boolean

    /**
     * macOS only, headful e2e: query TaoView's `NSTextInputClient` in one
     * snapshot. Fills [rangesOut] (length ≥ 5) with
     * `[markedLoc, markedLen, selectedLoc, selectedLen, charIndex]`
     * (`NSNotFound` is `-1`) and returns the marked-range substring, or
     * empty when the client returns `nil`.
     */
    @JvmStatic
    external fun nativeMacOsQueryTextInputClient(
        handle: Long,
        rangesOut: LongArray,
    ): String

    /**
     * macOS only, headful e2e: invoke `setMarkedText:selectedRange:replacementRange:`
     * on TaoView (the same entry IMKit uses).
     */
    @JvmStatic
    external fun nativeMacOsInjectMarkedText(
        handle: Long,
        text: String,
        selectedLocation: Int,
        selectedLength: Int,
    ): Boolean

    /**
     * macOS only, headful e2e: invoke `insertText:replacementRange:` on
     * TaoView. A negative [replacementLocation] injects `{NSNotFound, 0}`
     * (ordinary typing); a non-negative one replays the accent-picker
     * replacement commit (`insertText:"é" replacementRange:{caret-1, 1}`,
     * UTF-16 document-absolute).
     */
    @JvmStatic
    external fun nativeMacOsInjectInsertText(
        handle: Long,
        text: String,
        replacementLocation: Long,
        replacementLength: Long,
    ): Boolean

    /**
     * Windows counterpart of [nativeNsViewHandle]: returns the HWND so the JVM
     * can attach the GL render surface and apply custom decoration. Only resolvable on
     * Windows.
     */
    @JvmStatic
    external fun nativeHwndHandle(handle: Long): Long

    /**
     * Linux counterpart: returns `[kind, display, nativeWindow]` so the JVM can
     * attach an EGL context. `kind` is 0 = unavailable, 1 = Xlib, 2 = Wayland.
     * For Xlib, `display` is `Display*` and `nativeWindow` is the X11 `Window`
     * (XID). For Wayland, `display` is `wl_display*` and `nativeWindow` is
     * `wl_surface*`. Only resolvable on Linux.
     *
     * Prefer [TaoWindow.x11WindowId] / [TaoWindow.exportXdgForeignHandle] when
     * the goal is XDG Desktop Portal dialog parenting rather than EGL.
     */
    @JvmStatic
    external fun nativeLinuxHandles(handle: Long): LongArray?

    /**
     * Linux/Wayland only: export this window's surface via `xdg_foreign` and
     * return the **unprefixed** opaque handle string (for
     * `FileKitDialogParent.wayland` / portal `wayland:<handle>`).
     *
     * Blocks until the compositor delivers the handle or [timeoutMs] elapses.
     * Returns `null` on X11, when the window is not realized, or on failure.
     * Pair with [nativeLinuxUnexportXdgForeignHandle] when the portal dialogs
     * finish — FileKit borrows the handle and does not extend its lifetime.
     */
    @JvmStatic
    external fun nativeLinuxExportXdgForeignHandle(
        handle: Long,
        timeoutMs: Int,
    ): String?

    /**
     * Linux/Wayland only: drop the `xdg_foreign` export created by
     * [nativeLinuxExportXdgForeignHandle]. No-op when never exported or not
     * on Wayland.
     */
    @JvmStatic
    external fun nativeLinuxUnexportXdgForeignHandle(handle: Long)

    /**
     * Linux only: returns the underlying `GtkApplicationWindow*` (cast
     * to `Long`) for [handle], or 0 if the handle is unknown. Used by
     * the GtkWidget embedding path of [NativeView] to reparent
     * user-supplied widgets into Tao's content widget tree.
     */
    @JvmStatic
    external fun nativeLinuxGtkWindow(handle: Long): Long

    /**
     * Linux only, headful e2e: synthesize a `GdkEventScroll` on [handle] and
     * deliver it through GTK's `scroll-event` signal — the same path a real
     * mouse wheel uses.
     *
     * [direction] is a `GdkScrollDirection` (`0=UP`, `1=DOWN`, `2=LEFT`,
     * `3=RIGHT`, `4=SMOOTH`). Discrete directions force `delta_x`/`delta_y`
     * to zero (GTK 3's mouse-wheel payload). SMOOTH uses [deltaXMilli] /
     * [deltaYMilli] as thousandths.
     *
     * Coordinates are widget-local logical px. The caller should first
     * dispatch `CURSOR_MOVED` so Compose's last pointer sits over the
     * target — this function only delivers the scroll.
     *
     * Must run on the Tao / GTK main thread. Returns `false` when the handle
     * is unknown, the window is not realized, or [direction] is out of range.
     */
    @JvmStatic
    external fun nativeLinuxInjectGdkScroll(
        handle: Long,
        direction: Int,
        deltaXMilli: Int,
        deltaYMilli: Int,
        x: Int,
        y: Int,
    ): Boolean

    /**
     * Linux only: origin of the content area (the child GTK allocated inside
     * any client-side decorations) in logical toplevel coordinates, packed as
     * `(x shl 32) or (y and 0xffffffff)`. `(0, 0)` for plain undecorated
     * windows; the GTK theme's shadow margins when the yaru-style
     * hidden-titlebar CSD is active. Feed into
     * [NativeTaoEglBridge.nativeSetContentOffset].
     */
    @JvmStatic
    external fun nativeLinuxContentOrigin(handle: Long): Long

    /**
     * Linux only: rounds the GTK-drawn CSD frame (decoration node + window
     * background) to [radiusPx] on all four corners via a `GtkCssProvider`,
     * so the native frame matches the Compose-carved content corners exactly.
     */
    @JvmStatic
    external fun nativeLinuxSetCsdCornerRadius(
        handle: Long,
        radiusPx: Int,
    )

    /** Scale factor encoded as `(scale * 1000) as Int` to keep a single signature. */
    @JvmStatic
    external fun nativeScaleFactor(handle: Long): Int

    /**
     * Linux only: returns `[x, y, width, height]` of the primary monitor's
     * work area (full screen minus panels / docks) in physical pixels with a
     * top-left origin. Falls back to the full monitor geometry when GDK can't
     * report a work area (some Wayland compositors). Used to resolve
     * [androidx.compose.ui.window.WindowPosition.Aligned] for the initial
     * outer position of a window. Returns `null` if the handle is unknown.
     */
    @JvmStatic
    external fun nativeLinuxPrimaryMonitorWorkArea(handle: Long): LongArray?

    /**
     * Linux only: returns the primary monitor's scale factor encoded as
     * `(scale * 1000)`. Used as a scale source for the centring math when the
     * window's own scale factor is not yet resolvable.
     */
    @JvmStatic
    external fun nativeLinuxPrimaryMonitorScaleMilli(handle: Long): Int

    /**
     * Linux only: returns one descriptor per GDK monitor, encoded as documented
     * in [dev.nucleusframework.window.tao.TaoMonitor].
     *
     * [handle] may be `0` — monitors are a display-wide property, so the
     * default GDK display is used when no window is available. `null` when GDK
     * has no display.
     */
    @JvmStatic
    external fun nativeLinuxMonitors(handle: Long): Array<String>?

    /**
     * Linux only: wires [childHandle] as a GTK transient of [ownerHandle] via
     * `gtk_window_set_transient_for` (+ `skip_taskbar_hint`). Mirrors the Win32
     * `GWLP_HWNDPARENT` and AppKit `addChildWindow:` paths used by
     * `DecoratedDialog`. Pass `0` for [ownerHandle] to clear the relationship.
     *
     * [destroyWithOwner] adds `gtk_window_set_destroy_with_parent`, which is
     * the JDialog behaviour a dialog wants and the opposite of what a
     * satellite wants: a satellite outlives the window it is anchored to (the
     * workspace hands it to another one). GTK destroying it behind tao's back
     * leaves a live `TaoWindow` whose toplevel is gone — a window that reports
     * no geometry and can never be shown again.
     */
    @JvmStatic
    external fun nativeLinuxSetDialogOwner(
        childHandle: Long,
        ownerHandle: Long,
        destroyWithOwner: Boolean,
    )

    /**
     * Linux only: returns `[x, y, width, height]` of the window's outer
     * (decoration-inclusive) bounds in physical pixels with a top-left origin.
     * Matches the shape returned by the Windows / macOS counterparts so the
     * centring math in `DecoratedDialog` stays portable. Returns `null` when
     * the geometry isn't yet resolvable.
     */
    @JvmStatic
    external fun nativeLinuxGetWindowRect(handle: Long): LongArray?

    /** Synchronous — must be called on the macOS main thread during a press. */
    @JvmStatic
    external fun nativeDragWindow(handle: Long)

    /**
     * Begin an interactive resize drag in [direction]. Linux-only meaningful
     * today (X11 + Wayland through Tao's `Window::drag_resize_window`). The
     * Compose-side `ResizeFrameDecoration` calls this from `onPointerButton`
     * BEFORE forwarding the press to `scene.sendPointerEvent`, so it claims
     * clicks even on top of a Compose scrollbar — same architectural pattern
     * as JBR's `WLDecoratedPeer.startResize(...)`.
     *
     * Direction is the ordinal of `ResizeDirection`: 0=N, 1=S, 2=E, 3=W,
     * 4=NW, 5=NE, 6=SW, 7=SE.
     */
    @JvmStatic
    external fun nativeBeginResizeDrag(
        handle: Long,
        direction: Int,
    )

    @JvmStatic
    external fun nativeIsMaximized(handle: Long): Boolean

    /** Linux/GTK only: true when the compositor has tiled/snapped the window. */
    @JvmStatic
    external fun nativeIsTiled(handle: Long): Boolean

    @JvmStatic
    external fun nativeSetMaximized(
        handle: Long,
        maximized: Boolean,
    )

    @JvmStatic
    external fun nativeSetResizable(
        handle: Long,
        resizable: Boolean,
    )

    @JvmStatic
    external fun nativeSetMinimized(
        handle: Long,
        minimized: Boolean,
    )

    @JvmStatic
    external fun nativeSetAlwaysOnTop(
        handle: Long,
        alwaysOnTop: Boolean,
    )

    @JvmStatic
    external fun nativeSetAlwaysOnBottom(
        handle: Long,
        alwaysOnBottom: Boolean,
    )

    @JvmStatic
    external fun nativeSetFocusable(
        handle: Long,
        focusable: Boolean,
    )

    @JvmStatic
    external fun nativeSetIgnoreCursorEvents(
        handle: Long,
        ignore: Boolean,
    )

    @JvmStatic
    external fun nativeSetVisibleOnAllWorkspaces(
        handle: Long,
        visible: Boolean,
    )

    /**
     * Raises the window to the top of the z-order, restores it if minimized,
     * and gives it keyboard focus. Maps to Tao's `Window::set_focus()` which
     * routes through `SetForegroundWindow` on Win32, `[NSWindow makeKeyAndOrderFront:]`
     * on macOS, and `gtk_window_present_with_time` on Linux.
     */
    @JvmStatic
    external fun nativeFocus(handle: Long)

    /** [width]/[height] in logical pixels; pass negative values to clear. */
    @JvmStatic
    external fun nativeSetMinInnerSize(
        handle: Long,
        width: Double,
        height: Double,
    )

    /** [width]/[height] in logical pixels; pass negative values to clear. */
    @JvmStatic
    external fun nativeSetMaxInnerSize(
        handle: Long,
        width: Double,
        height: Double,
    )

    /** [pixels] is row-major premultiplied RGBA. Empty array clears the icon. */
    @JvmStatic
    external fun nativeSetWindowIcon(
        handle: Long,
        width: Int,
        height: Int,
        pixels: ByteArray,
    )

    /** Logical pixels. */
    @JvmStatic
    external fun nativeSetInnerSize(
        handle: Long,
        width: Double,
        height: Double,
    )

    /** Logical pixels. */
    @JvmStatic
    external fun nativeSetOuterPosition(
        handle: Long,
        x: Double,
        y: Double,
    )

    /**
     * Linux only: anchors a popup overlay (`popupOf`) at a logical point of
     * its parent window through GDK's `move_to_rect`, so GDK maps it as a
     * compositor-positioned `xdg_popup` — see [TaoWindow.anchorPopupInParent].
     */
    @JvmStatic
    external fun nativeLinuxPopupAnchor(
        handle: Long,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        shadowLeft: Int,
        shadowTop: Int,
        shadowRight: Int,
        shadowBottom: Int,
    )

    @JvmStatic
    external fun nativeIsFullscreen(handle: Long): Boolean

    @JvmStatic
    external fun nativeSetFullscreen(
        handle: Long,
        fullscreen: Boolean,
    )

    /**
     * Sets the OS cursor for the window. [code] follows [TaoCursorIcon].
     * Callers go through [setCursorIcon], which records the request first.
     */
    @JvmStatic
    external fun nativeSetCursorIcon(
        handle: Long,
        code: Int,
    )

    /**
     * The last cursor code requested per window handle, exactly as it was
     * handed to [nativeSetCursorIcon]. The platform cursor itself cannot be
     * read back portably (and never under Xvfb), so this is what the headful
     * suite asserts against: a `BasicTextField` under a still pointer must
     * have left a `TEXT` here, and a native view under it must not have
     * flipped it back.
     */
    val lastCursorIcon: java.util.concurrent.ConcurrentHashMap<Long, Int> = java.util.concurrent.ConcurrentHashMap()

    /** Records the request in [lastCursorIcon] and applies it. */
    fun setCursorIcon(
        handle: Long,
        code: Int,
    ) {
        lastCursorIcon[handle] = code
        nativeSetCursorIcon(handle, code)
    }

    /**
     * Anchors the platform IME UI at the given window-local rect in *physical
     * pixels* (top-left origin), so preedit and candidate windows follow the
     * caret.
     *
     * - **macOS**: tao's stock `firstRectForCharacterRange:` returns 0×0; the
     *   rect is stored for a swizzled implementation to return.
     * - **Windows** (#558): forwarded to `Window::set_ime_position`, which
     *   sets both `COMPOSITIONFORM` and `CANDIDATEFORM` on the input context.
     */
    @JvmStatic
    external fun nativeSetImeRect(
        handle: Long,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
    )

    /**
     * macOS only: pushes the focused field's committed text (a bounded
     * window), the window's document-absolute offset and the selection to
     * the native `NSTextInputClient` cache — all offsets in UTF-16 code
     * units. AppKit reads `selectedRange` / `attributedSubstringForProposedRange`
     * from this cache (Chromium parity: the renderer→browser selection +
     * surrounding-text push), which is what lets the press-and-hold accent
     * picker engage and commit through `insertText:replacementRange:`.
     *
     * A negative [selectionStart] invalidates the cache (no focused field).
     */
    @JvmStatic
    external fun nativeSetImeDocument(
        handle: Long,
        text: String,
        offset: Long,
        selectionStart: Long,
        selectionEnd: Long,
    )

    /** Calls `[view.inputContext activate]` for TaoView's NSTextInputClient. */
    @JvmStatic
    external fun nativeActivateInputContext(handle: Long)

    // ── Accessibility (macOS) ──────────────────────────────────────────────
    //
    // The Compose Semantics tree is observed by [TaoAccessibilityController]
    // and pushed here as a binary [ByteArray]. Native parses, projects to
    // NucleusA11yElement objects, and exposes them to AppKit / VoiceOver.

    // The a11y API takes the NSView pointer directly (not the window handle)
    // because EVENT_DESTROYED is dispatched from inside Rust's WINDOWS lock —
    // any reentrant `WINDOWS.lock()` from JNI on the same thread would
    // deadlock the Tao event loop. The JVM caches the NSView at attach time
    // and passes it back unchanged on every call.

    @JvmStatic
    external fun nativeA11yAttach(nsView: Long)

    @JvmStatic
    external fun nativeA11yDetach(nsView: Long)

    @JvmStatic
    external fun nativeA11yApplySnapshot(
        nsView: Long,
        bytes: ByteArray,
    ): Boolean

    /**
     * Linux-only: apply a wire-format v7 *partial* snapshot. Only the nodes
     * whose data or children list changed since the previous push are
     * included; AccessKit merges them into its existing tree. macOS / Windows
     * stubs return false (their parsers are still v4 and reject anything
     * else, which is acceptable on this branch — the shared encoder targets
     * the Linux path).
     */
    @JvmStatic
    external fun nativeA11yApplyPartialSnapshot(
        nsView: Long,
        bytes: ByteArray,
    ): Boolean

    @JvmStatic
    external fun nativeA11yPostFocusChanged(
        nsView: Long,
        nodeId: Long,
    )

    /**
     * macOS-only: publishes Compose's non-editable selection
     * (`SelectionContainer`) as the focused element's `AXSelectedText` for
     * cross-process readers (PopClip). Empty string clears it. No-op on other
     * platforms. See [TaoAccessibilityController.setExternalSelection].
     */
    @JvmStatic
    external fun nativeA11ySetExternalSelection(
        nsView: Long,
        text: String,
    )

    /**
     * Linux-only: pushes outer + inner window geometry (in screen-relative
     * physical pixels) into AccessKit's root-bounds slot. Required because
     * AT-SPI's `Component.GetExtents(SCREEN)` queries return window-local
     * coordinates without it. We're an XWayland client thanks to
     * `GDK_BACKEND=x11`, so XGetGeometry / XTranslateCoordinates produce
     * accurate screen positions even on Wayland.
     *
     * No-op on macOS / Windows.
     */
    @JvmStatic
    external fun nativeA11ySetRootBounds(
        nsView: Long,
        outerX: Long,
        outerY: Long,
        outerW: Long,
        outerH: Long,
        innerX: Long,
        innerY: Long,
        innerW: Long,
        innerH: Long,
    )

    /**
     * Linux-only: ask Rust to resolve the X11 window's screen-space origin
     * via `XGetGeometry` + `XTranslateCoordinates(window → root)` and push
     * it to AccessKit. This gives Orca's flat-review and screen-magnifiers
     * accurate on-screen coordinates — without it, AccessKit reports
     * window-local bounds and the highlight floats around (0,0).
     *
     * `display` and `xid` come from [nativeLinuxHandles].
     */
    @JvmStatic
    external fun nativeA11yResolveX11Bounds(
        nsView: Long,
        display: Long,
        xid: Long,
    )

    /**
     * Linux-only: forwards X11 focus state to AccessKit's adapter so AT-SPI's
     * `STATE_ACTIVE` on the toplevel matches the actual window focus. On
     * macOS / Windows the platform UIA / NSAccessibility hooks observe focus
     * directly.
     */
    @JvmStatic
    external fun nativeA11ySetWindowFocus(
        nsView: Long,
        focused: Boolean,
    )

    /**
     * Reads the `voiceOverEnabled` user default. Returns true when VoiceOver
     * is currently running (or has been left enabled). Cheap CFPreferences
     * read; safe to poll. Updates are not pushed — callers may re-query at
     * any point but the value won't change between polls in the same tick.
     */
    @JvmStatic
    external fun nativeA11yIsVoiceOverRunning(): Boolean

    /**
     * Linux only: override the AT-SPI application name reported through
     * `org.a11y.atspi.Application.toolkitName` and the `Accessible.Name` of
     * the root. Without this, accesskit_unix uses `current_exe()` — which on
     * the JVM is just "java", so screen readers / Accerciser show the app
     * incorrectly. Must be called before the first adapter is constructed.
     * No-op on macOS and Windows.
     */
    @JvmStatic
    external fun nativeA11ySetAppName(name: String)

    /**
     * Returns true while at least one accessibility client (VoiceOver,
     * Switch Control, AppleScript / System Events, Accessibility Inspector,
     * etc.) has touched our tree within the last ~5 minutes. Mirrors
     * Compose Desktop's `AccessibilityUsage` idle window. Used by
     * [TaoAccessibilityController] to skip pushing snapshots when no client
     * is listening.
     */
    @JvmStatic
    external fun nativeA11yIsActive(): Boolean

    /**
     * Atomically consumes the "force resync" flag set by the native side
     * whenever an AX query lands while pushes are being skipped. Returns
     * `true` once and the flag is cleared — observer must push a fresh
     * snapshot on the same tick.
     */
    @JvmStatic
    external fun nativeA11yConsumeResync(): Boolean

    /** Tells the native side that a snapshot was just pushed. */
    @JvmStatic
    external fun nativeA11yNotePushed()

    /**
     * Called from native (`macos/a11y.m` → `nucleus_tao_a11y_invoke_action`)
     * on the macOS main thread when VoiceOver triggers an action. Routed to
     * the registered [TaoAccessibilityController] for the given window.
     *
     * [action] mirrors the `NucleusA11yAction` bitmask: 1=click, 2=increment,
     * 4=decrement, 8=setText. Exactly one bit is set per call.
     */
    @JvmStatic
    @Suppress("unused") // called from JNI
    fun dispatchA11yAction(
        handle: Long,
        nodeId: Long,
        action: Int,
    ) {
        fatalUpcall { TaoAccessibilityRegistry.dispatchAction(handle, nodeId, action) }
    }

    @JvmStatic
    @Suppress("unused") // called from JNI (macos/a11y.m → nucleus_tao_a11y_invoke_action)
    fun dispatchA11yActionByNsView(
        nsView: Long,
        nodeId: Long,
        action: Int,
    ) {
        fatalUpcall { TaoAccessibilityRegistry.dispatchActionByNsView(nsView, nodeId, action) }
    }

    @JvmStatic
    @Suppress("unused") // called from JNI (macos/a11y.m → nucleus_tao_a11y_set_text)
    fun dispatchA11ySetText(
        nsView: Long,
        nodeId: Long,
        text: String,
    ) {
        fatalUpcall { TaoAccessibilityRegistry.dispatchSetText(nsView, nodeId, text) }
    }

    @JvmStatic
    @Suppress("unused") // called from JNI (macos/a11y.m → nucleus_tao_a11y_set_selection)
    fun dispatchA11ySetSelection(
        nsView: Long,
        nodeId: Long,
        start: Int,
        end: Int,
    ) {
        fatalUpcall { TaoAccessibilityRegistry.dispatchSetSelection(nsView, nodeId, start, end) }
    }

    @JvmStatic
    @Suppress("unused") // called from JNI (macos/a11y.m → nucleus_tao_a11y_invoke_custom_action)
    fun dispatchA11yCustomAction(
        nsView: Long,
        nodeId: Long,
        index: Int,
    ) {
        fatalUpcall { TaoAccessibilityRegistry.dispatchCustomAction(nsView, nodeId, index) }
    }

    @JvmStatic
    @Suppress("unused") // called from JNI (macos/a11y.m → nucleus_tao_a11y_scroll_by)
    fun dispatchA11yScrollBy(
        nsView: Long,
        nodeId: Long,
        dx: Float,
        dy: Float,
    ) {
        fatalUpcall { TaoAccessibilityRegistry.dispatchScrollBy(nsView, nodeId, dx, dy) }
    }

    /**
     * Linux-only: AT-SPI `Value.SetCurrentValue` dispatcher. AccessKit's
     * Value interface routes through `Action::SetValue` with a NumericValue
     * payload; we forward the absolute value to Compose's SetProgress action.
     */
    @JvmStatic
    @Suppress("unused") // called from JNI (a11y_linux.rs → forward_action_to_jvm)
    fun dispatchA11ySetValue(
        nsView: Long,
        nodeId: Long,
        value: Double,
    ) {
        fatalUpcall { TaoAccessibilityRegistry.dispatchSetValue(nsView, nodeId, value) }
    }
}
