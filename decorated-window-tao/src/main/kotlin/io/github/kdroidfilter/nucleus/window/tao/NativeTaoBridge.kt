package io.github.kdroidfilter.nucleus.window.tao

import io.github.kdroidfilter.nucleus.core.runtime.NativeLibraryLoader

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
internal object NativeTaoBridge {
    private val loaded = NativeLibraryLoader.load(LIBRARY_NAME, NativeTaoBridge::class.java)

    val isLoaded: Boolean get() = loaded

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

    /**
     * Installs the macOS `NSAppleEventManager` handler for `kInternetEventClass
     * / kAEGetURL`. Must be called *before* [nativeRunBlocking] so the
     * cold-start URL (app launched via deep link) is delivered. No-op on
     * Windows / Linux. The Java callback receives URLs through
     * [dispatchDeepLink].
     */
    @JvmStatic
    external fun nativeAppleEventsInstall()

    @JvmStatic
    @Suppress("unused") // called from JNI (macos/apple_events.m → nucleus_tao_apple_events_dispatch)
    fun dispatchDeepLink(uri: String) {
        TaoDeepLinkBridge.onUrlFromNative(uri)
    }

    /**
     * Returns the underlying NSView pointer for the given window handle. Must
     * be called on the macOS main thread. Returns 0 if the window does not
     * exist (yet) or has been closed. Only resolvable on macOS — calling on
     * other platforms throws `UnsatisfiedLinkError`.
     */
    @JvmStatic
    external fun nativeNsViewHandle(handle: Long): Long

    /**
     * Windows counterpart of [nativeNsViewHandle]: returns the HWND so the JVM
     * can attach a WGL context and apply custom decoration. Only resolvable on
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
     */
    @JvmStatic
    external fun nativeLinuxHandles(handle: Long): LongArray?

    /**
     * Linux only: returns the underlying `GtkApplicationWindow*` (cast
     * to `Long`) for [handle], or 0 if the handle is unknown. Used by
     * the GtkWidget embedding path of [NativeView] to reparent
     * user-supplied widgets into Tao's content widget tree.
     */
    @JvmStatic
    external fun nativeLinuxGtkWindow(handle: Long): Long

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
     * Linux only: wires [childHandle] as a GTK transient of [ownerHandle] via
     * `gtk_window_set_transient_for` (+ `skip_taskbar_hint` and
     * `destroy_with_parent`). Mirrors the Win32 `GWLP_HWNDPARENT` and AppKit
     * `addChildWindow:` paths used by `DecoratedDialog`. Pass `0` for
     * [ownerHandle] to clear the relationship.
     */
    @JvmStatic
    external fun nativeLinuxSetDialogOwner(
        childHandle: Long,
        ownerHandle: Long,
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

    @JvmStatic
    external fun nativeIsMaximized(handle: Long): Boolean

    @JvmStatic
    external fun nativeSetMaximized(
        handle: Long,
        maximized: Boolean,
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
    external fun nativeSetFocusable(
        handle: Long,
        focusable: Boolean,
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

    @JvmStatic
    external fun nativeIsFullscreen(handle: Long): Boolean

    @JvmStatic
    external fun nativeSetFullscreen(
        handle: Long,
        fullscreen: Boolean,
    )

    /** Sets the OS cursor for the window. [code] follows [TaoCursorIcon]. */
    @JvmStatic
    external fun nativeSetCursorIcon(
        handle: Long,
        code: Int,
    )

    /**
     * Anchors macOS IME UI (accent picker, dead-key feedback, candidate windows)
     * at the given window-local rect in *physical pixels* (top-left origin).
     * AppKit's press-and-hold logic requires this rect to have non-zero size —
     * Tao's stock `firstRectForCharacterRange:` returns 0×0, which we override.
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
     * Calls `[view.inputContext activate]`. Required to trigger AppKit's
     * press-and-hold accent picker: without it, the inputContext is not the
     * "current" one and `_NSKeyBindingManager` skips the marked-text phase.
     */
    @JvmStatic
    external fun nativeActivateInputContext(handle: Long)

    /**
     * Adds a transparent `NSTextView` overlay as a subview of the TaoView.
     * AppKit's press-and-hold logic only engages for views whose lineage
     * includes `NSTextView`; the overlay satisfies that check while forwarding
     * every NSTextInputClient call to the underlying TaoView. Idempotent.
     */
    @JvmStatic
    external fun nativeAttachTextOverlay(handle: Long)

    /**
     * Routes key events through the NSTextView overlay (`focused = true`) so
     * `_NSKeyBindingManager` engages press-and-hold, or back to TaoView
     * (`focused = false`) once a Compose TextField loses focus.
     */
    @JvmStatic
    external fun nativeFocusTextOverlay(focused: Boolean)

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
        TaoAccessibilityRegistry.dispatchAction(handle, nodeId, action)
    }

    @JvmStatic
    @Suppress("unused") // called from JNI (macos/a11y.m → nucleus_tao_a11y_invoke_action)
    fun dispatchA11yActionByNsView(
        nsView: Long,
        nodeId: Long,
        action: Int,
    ) {
        TaoAccessibilityRegistry.dispatchActionByNsView(nsView, nodeId, action)
    }

    @JvmStatic
    @Suppress("unused") // called from JNI (macos/a11y.m → nucleus_tao_a11y_set_text)
    fun dispatchA11ySetText(
        nsView: Long,
        nodeId: Long,
        text: String,
    ) {
        TaoAccessibilityRegistry.dispatchSetText(nsView, nodeId, text)
    }

    @JvmStatic
    @Suppress("unused") // called from JNI (macos/a11y.m → nucleus_tao_a11y_set_selection)
    fun dispatchA11ySetSelection(
        nsView: Long,
        nodeId: Long,
        start: Int,
        end: Int,
    ) {
        TaoAccessibilityRegistry.dispatchSetSelection(nsView, nodeId, start, end)
    }

    @JvmStatic
    @Suppress("unused") // called from JNI (macos/a11y.m → nucleus_tao_a11y_invoke_custom_action)
    fun dispatchA11yCustomAction(
        nsView: Long,
        nodeId: Long,
        index: Int,
    ) {
        TaoAccessibilityRegistry.dispatchCustomAction(nsView, nodeId, index)
    }

    @JvmStatic
    @Suppress("unused") // called from JNI (macos/a11y.m → nucleus_tao_a11y_scroll_by)
    fun dispatchA11yScrollBy(
        nsView: Long,
        nodeId: Long,
        dx: Float,
        dy: Float,
    ) {
        TaoAccessibilityRegistry.dispatchScrollBy(nsView, nodeId, dx, dy)
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
        TaoAccessibilityRegistry.dispatchSetValue(nsView, nodeId, value)
    }
}

/** Cursor icon codes mirrored 1:1 with the Rust `cursor_from_code` table. */
@Suppress("MagicNumber")
object TaoCursorIcon {
    const val DEFAULT: Int = 0
    const val TEXT: Int = 1
    const val HAND: Int = 2
    const val CROSSHAIR: Int = 3
    const val WAIT: Int = 4
    const val MOVE: Int = 5
    const val NOT_ALLOWED: Int = 6
    const val HELP: Int = 7
    const val PROGRESS: Int = 8
    const val EW_RESIZE: Int = 9
    const val NS_RESIZE: Int = 10
    const val NESW_RESIZE: Int = 11
    const val NWSE_RESIZE: Int = 12
}

/** Mirrors the event constants in `nucleus_tao` (`lib.rs`). */
@Suppress("MagicNumber")
object TaoEventCode {
    const val LAUNCHED: Int = 1
    const val RESIZED: Int = 2
    const val CLOSE_REQUESTED: Int = 3
    const val DESTROYED: Int = 4
    const val REDRAW_REQUESTED: Int = 5
    const val FOCUSED: Int = 6
    const val UNFOCUSED: Int = 7
    const val SCALE_FACTOR_CHANGED: Int = 8

    const val CURSOR_MOVED: Int = 10
    const val CURSOR_LEFT: Int = 11
    const val MOUSE_DOWN: Int = 12
    const val MOUSE_UP: Int = 13
    const val KEY_DOWN: Int = 14
    const val KEY_UP: Int = 15
    const val WINDOW_READY: Int = 16
    const val SCROLL_LINE: Int = 17
    const val SCROLL_PIXEL: Int = 18
    const val KEY_TYPED: Int = 19

    /**
     * Fired once per Tao event-loop iteration once every in-flight event has
     * been processed. We use it to drain `TaoMainDispatcher`'s task queue so
     * the Compose Recomposer can run on the same thread as the Tao loop.
     */
    const val MAIN_EVENTS_CLEARED: Int = 20

    /** `a`/`b` carry `x`/`y` in physical pixels. */
    const val MOVED: Int = 21
}

/** Trackpad gesture kind reported by [NativeTaoBridge.EventCallback.onTrackpadGesture]. */
@Suppress("MagicNumber")
object TaoTrackpadGesture {
    const val MAGNIFY: Int = 0
    const val ROTATE: Int = 1
    const val SMART_MAGNIFY: Int = 2
}

/** Trackpad gesture phase reported by [NativeTaoBridge.EventCallback.onTrackpadGesture]. */
@Suppress("MagicNumber")
object TaoTrackpadPhase {
    const val BEGAN: Int = 0
    const val CHANGED: Int = 1
    const val ENDED: Int = 2
    const val CANCELLED: Int = 3
}

/** Modifier-state bitmask that mirrors the Rust side. */
@Suppress("MagicNumber")
object TaoModifierMask {
    const val SHIFT: Int = 1 shl 0
    const val CONTROL: Int = 1 shl 1
    const val ALT: Int = 1 shl 2
    const val META: Int = 1 shl 3
}

/** AWT-equivalent `KeyEvent.KEY_LOCATION_*` constants we accept from Rust. */
@Suppress("MagicNumber")
object TaoKeyLocation {
    const val STANDARD: Int = 1
    const val LEFT: Int = 2
    const val RIGHT: Int = 3
    const val NUMPAD: Int = 4
}

@Suppress("MagicNumber")
object TaoMouseButton {
    const val LEFT: Int = 0
    const val RIGHT: Int = 1
    const val MIDDLE: Int = 2
    const val OTHER: Int = 3
}
