package io.github.kdroidfilter.nucleus.window.tao

import io.github.kdroidfilter.nucleus.core.runtime.NativeLibraryLoader

private const val LIBRARY_NAME = "nucleus_tao"

/**
 * Direct JNI bridge over the Tao windowing library.
 *
 * macOS-only Phase 2. The event loop owned by [nativeRunBlocking] must run on
 * the macOS main thread (process thread 0). This is guaranteed by GraalVM
 * native-image — it is **not** the case on a regular JVM unless the binary is
 * launched with `-XstartOnFirstThread`.
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
        fun onEvent(handle: Long, code: Int, a: Int, b: Int)

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

    @JvmStatic
    external fun nativeIsAvailable(): Boolean

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

    /** Scale factor encoded as `(scale * 1000) as Int` to keep a single signature. */
    @JvmStatic
    external fun nativeScaleFactor(handle: Long): Int

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
    external fun nativeA11yApplySnapshot(nsView: Long, bytes: ByteArray): Boolean

    @JvmStatic
    external fun nativeA11yPostFocusChanged(nsView: Long, nodeId: Long)

    /**
     * Reads the `voiceOverEnabled` user default. Returns true when VoiceOver
     * is currently running (or has been left enabled). Cheap CFPreferences
     * read; safe to poll. Updates are not pushed — callers may re-query at
     * any point but the value won't change between polls in the same tick.
     */
    @JvmStatic
    external fun nativeA11yIsVoiceOverRunning(): Boolean

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
     * Called from native (`objc/a11y.m` → `nucleus_tao_a11y_invoke_action`)
     * on the macOS main thread when VoiceOver triggers an action. Routed to
     * the registered [TaoAccessibilityController] for the given window.
     *
     * [action] mirrors the `NucleusA11yAction` bitmask: 1=click, 2=increment,
     * 4=decrement, 8=setText. Exactly one bit is set per call.
     */
    @JvmStatic
    @Suppress("unused") // called from JNI
    fun dispatchA11yAction(handle: Long, nodeId: Long, action: Int) {
        TaoAccessibilityRegistry.dispatchAction(handle, nodeId, action)
    }

    @JvmStatic
    @Suppress("unused") // called from JNI (objc/a11y.m → nucleus_tao_a11y_invoke_action)
    fun dispatchA11yActionByNsView(nsView: Long, nodeId: Long, action: Int) {
        TaoAccessibilityRegistry.dispatchActionByNsView(nsView, nodeId, action)
    }

    @JvmStatic
    @Suppress("unused") // called from JNI (objc/a11y.m → nucleus_tao_a11y_set_text)
    fun dispatchA11ySetText(nsView: Long, nodeId: Long, text: String) {
        TaoAccessibilityRegistry.dispatchSetText(nsView, nodeId, text)
    }

    @JvmStatic
    @Suppress("unused") // called from JNI (objc/a11y.m → nucleus_tao_a11y_set_selection)
    fun dispatchA11ySetSelection(nsView: Long, nodeId: Long, start: Int, end: Int) {
        TaoAccessibilityRegistry.dispatchSetSelection(nsView, nodeId, start, end)
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
