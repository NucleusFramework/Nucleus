@file:Suppress("MagicNumber")

package dev.nucleusframework.window.tao.ffi

import dev.nucleusframework.core.runtime.NativeLibraryLoader

/**
 * JNI bridge to the Linux standalone popup panel helper
 * (`linux/nucleus_tao_linux_popup.c` + `nucleus_tao_linux_popup_xdnd.c`,
 * shipped as `libnucleus_tao_linux_popup.so`).
 *
 * The Linux twin of [PopupNativeBridgeWindows] / [PopupNativeBridge]
 * (macOS), standalone panels only — there is no owner-based variant on
 * Linux. The native side creates a top-level, ownerless, override-redirect
 * ARGB32 X11 window on its own `XOpenDisplay` connection: an independent
 * X client that works even while the app itself is a native Wayland client
 * (through XWayland). [nativeIsAvailable] reports whether an X server is
 * reachable; without one (rare Wayland-only setups) callers must fall back
 * to a regular window.
 *
 * Rendering is NOT handled here: the Kotlin host feeds [nativeDisplayPtr] +
 * [nativeWindowXid] to `NativeTaoEglBridge.nativeAttachX11`, which resolves
 * the same `EGLDisplay` and matches the ARGB config the panel's visual was
 * derived from.
 *
 * Threading: every entry point must run on the Tao main thread (the
 * composable wrapper guarantees this) — it owns the native command
 * connection. Input callbacks arrive on the panel's own X event thread.
 *
 * Wire format: coordinates are physical pixels in the X11 coordinate
 * space, top-left origin. Global screen coordinates for the frame,
 * panel-local for pointer events.
 */
@Suppress("TooManyFunctions")
internal object PopupNativeBridgeLinux {
    private const val LIBRARY_NAME = "nucleus_tao_linux_popup"

    val isLoaded: Boolean = NativeLibraryLoader.load(LIBRARY_NAME, PopupNativeBridgeLinux::class.java)

    /**
     * Whether a standalone panel can be created: an X server (native X11 or
     * XWayland) must be reachable through `DISPLAY`. Cached natively after
     * the first probe.
     */
    @JvmStatic
    external fun nativeIsAvailable(): Boolean

    /**
     * The command connection's `Display*` — pass to
     * `NativeTaoEglBridge.nativeAttachX11` together with [nativeWindowXid].
     */
    @JvmStatic
    external fun nativeDisplayPtr(): Long

    /**
     * `Xft.dpi / 96` from the X resource database, 1.0 when unset. X clients
     * live in the X coordinate space (logical under XWayland), so GDK's
     * Wayland monitor scale must NOT be used for panel geometry. Uses its own
     * short-lived connection — callable from any thread.
     */
    @JvmStatic
    external fun nativeScale(): Float

    /**
     * Primary-monitor work area `[x, y, width, height]` in X11 pixels (XRandR
     * primary intersected with EWMH `_NET_WORKAREA`), or `null` when no X
     * server is reachable. Backs `TaoScreenGeometry.primaryMonitorWorkAreaPx`
     * when no realized Tao window exists (panel-only tray apps). Uses its own
     * short-lived connection — callable from any thread.
     */
    @JvmStatic
    external fun nativePrimaryWorkArea(): LongArray?

    /**
     * Creates the hidden override-redirect ARGB32 panel window and starts
     * its event thread. Coordinates are global screen pixels. Returns an
     * opaque handle owning the panel, or 0 when unavailable; release via
     * [nativeRelease].
     */
    @JvmStatic
    external fun nativeCreatePanel(
        xPx: Int,
        yPx: Int,
        widthPx: Int,
        heightPx: Int,
    ): Long

    /** The panel's X11 window XID, for `NativeTaoEglBridge.nativeAttachX11`. */
    @JvmStatic
    external fun nativeWindowXid(panel: Long): Long

    /** Moves/resizes the panel in global screen pixels (top-left origin). */
    @JvmStatic
    external fun nativeSetFrameOnScreen(
        panel: Long,
        xPx: Int,
        yPx: Int,
        widthPx: Int,
        heightPx: Int,
    )

    /** Maps (raised) or unmaps the panel. */
    @JvmStatic
    external fun nativeSetPanelVisible(
        panel: Long,
        visible: Boolean,
    )

    /**
     * While focusable, a click inside the panel calls
     * `XSetInputFocus(RevertToParent)` — override-redirect windows never
     * receive focus from the WM, so the panel takes it explicitly (the
     * Windows `takeKeyboardFocus()` equivalent).
     */
    @JvmStatic
    external fun nativeSetFocusable(
        panel: Long,
        focusable: Boolean,
    )

    /** Applies a [TaoCursorIcon] code to the panel window. */
    @JvmStatic
    external fun nativeSetPanelCursor(
        panel: Long,
        iconCode: Int,
    )

    /**
     * Receives raw X11 events forwarded by the panel's event thread when
     * the user interacts inside the panel's bounds. Coordinates are
     * panel-local pixels (top-left origin) — matches what
     * `ComposeScene.sendPointerEvent` expects. Same shape as the macOS /
     * Windows popup callbacks ([TaoNativeWireFormat] codes).
     */
    interface EventCallback {
        /** [type] = 1 down, 2 up, 3 move. [button] = 0 none, 1 primary, 2 secondary. */
        @Suppress("FunctionParameterNaming")
        fun onPointerEvent(
            type: Int,
            x: Float,
            y: Float,
            button: Int,
            modifiers: Int,
        )

        /** One line per wheel click; positive Y scrolls the content down. */
        @Suppress("FunctionParameterNaming")
        fun onScroll(
            x: Float,
            y: Float,
            dx: Float,
            dy: Float,
        )

        /**
         * [type] = 1 down, 2 up. [vkCode] is an X11 keysym (Latin one when
         * the active layout has none — see `vk_keysym_for` in the C side);
         * translated by `linuxNativeKeyToAwt`.
         */
        @Suppress("FunctionParameterNaming")
        fun onKeyEvent(
            type: Int,
            vkCode: Int,
            codePoint: Int,
            modifiers: Int,
        )
    }

    /**
     * Receives a callback whenever a mouse press lands outside the visible
     * panel. Backed by XI2 raw ButtonPress on the root window — the X11
     * analog of the Windows `WH_MOUSE_LL` hook (observe-only, nothing is
     * consumed). Fully global on X11 sessions; under XWayland raw events
     * only fire while X11 surfaces have input focus.
     */
    interface OutsideClickListener {
        /** [type] = 1 (always Press). [button] = 1 primary, 2 secondary, 3 other. */
        fun onOutsideClick(
            type: Int,
            button: Int,
        )
    }

    /** Installs the JNI [EventCallback] on the panel. Pass `null` to remove. */
    @JvmStatic
    external fun nativeSetEventCallback(
        panel: Long,
        callback: EventCallback?,
    )

    /** Installs the outside-click monitor (see [OutsideClickListener]). */
    @JvmStatic
    external fun nativeInstallOutsideClickMonitor(
        panel: Long,
        listener: OutsideClickListener,
    )

    /** Removes any previously installed outside-click monitor for this panel. */
    @JvmStatic
    external fun nativeUninstallOutsideClickMonitor(panel: Long)

    /**
     * Receives XDND callbacks for this raw X11 panel. Same shape as
     * [NativeTaoLinuxDndBridge.Callback] / the Windows IDropTarget bridge so
     * [dev.nucleusframework.window.tao.dnd.TaoSceneDnD] can stay shared.
     *
     * Invoked on the panel's X event thread — hop to the Tao main thread
     * before touching a Compose scene. [handle] is the panel pointer.
     *
     * Return values for `onDragEnter`/`onDragOver`/`onDrop`:
     *   - [DROP_EFFECT_NONE] — reject the drag/drop
     *   - [DROP_EFFECT_COPY] — accept as a copy
     *
     * Must be a named class, not a lambda or anonymous object: GraalVM's
     * `GetMethodID` does not pick up inherited interface methods on anonymous
     * classes.
     */
    interface DnDCallback {
        @Suppress("FunctionParameterNaming")
        fun onDragEnter(
            handle: Long,
            x: Int,
            y: Int,
            modState: Int,
            hasFiles: Boolean,
        ): Int

        @Suppress("FunctionParameterNaming")
        fun onDragOver(
            handle: Long,
            x: Int,
            y: Int,
            modState: Int,
            hasFiles: Boolean,
        ): Int

        fun onDragLeave(handle: Long)

        @Suppress("FunctionParameterNaming")
        fun onDrop(
            handle: Long,
            x: Int,
            y: Int,
            modState: Int,
            files: Array<String>?,
        ): Int
    }

    /**
     * Installs the inbound XDND callback. Pass `null` to remove. The panel
     * advertises `XdndAware` at creation, so file managers can discover it
     * even before a callback is set — without one, every Status is a reject.
     */
    @JvmStatic
    external fun nativeSetDnDCallback(
        panel: Long,
        callback: DnDCallback?,
    )

    /**
     * Test-only XDND source: acts as a second X client and drops [files]
     * onto [panel] (`XdndEnter` → `XdndPosition` → `XdndDrop`, serving
     * `text/uri-list` on `SelectionRequest`). Returns [DROP_EFFECT_COPY]
     * after `XdndFinished`, or [DROP_EFFECT_NONE] on timeout / protocol
     * failure. Production code must not call this.
     */
    @JvmStatic
    external fun nativeSmokeXdndDrop(
        panel: Long,
        files: Array<String>,
    ): Int

    /**
     * Stops the event thread, destroys the X window and frees the panel.
     * The EGL attachment must have been detached first
     * (`NativeTaoEglBridge.nativeDetach`).
     */
    @JvmStatic
    external fun nativeRelease(panel: Long)

    const val DROP_EFFECT_NONE: Int = 0
    const val DROP_EFFECT_COPY: Int = 1
    const val DROP_EFFECT_MOVE: Int = 2
    const val DROP_EFFECT_LINK: Int = 4
}
