package dev.nucleusframework.window.tao.ffi

import dev.nucleusframework.core.runtime.NativeLibraryLoader

private const val LIBRARY_NAME = "nucleus_tao_windows_deco"

/**
 * JNI bridge to the WndProc subclass that gives a Tao HWND a custom title bar
 * (client-area extension via `WM_NCCALCSIZE`, hit-test routing via
 * `WM_NCHITTEST`, DWM shadow via `DwmExtendFrameIntoClientArea`).
 *
 * Mirrors the API of the legacy AWT backend's Windows decoration bridge,
 * minus the Skiko-AWT child-window plumbing (Tao renders into the HWND
 * directly via ANGLE).
 */
@Suppress("TooManyFunctions")
internal object NativeTaoWindowsDecoBridge {
    private val loaded = NativeLibraryLoader.load(LIBRARY_NAME, NativeTaoWindowsDecoBridge::class.java)

    val isLoaded: Boolean get() = loaded

    @JvmStatic
    external fun nativeInstallDecoration(
        hwnd: Long,
        titleBarHeightPx: Int,
    )

    @JvmStatic
    external fun nativeUninstallDecoration(hwnd: Long)

    @JvmStatic
    external fun nativeSetTitleBarHeight(
        hwnd: Long,
        heightPx: Int,
    )

    /**
     * Fully borderless DWM chrome for overlay/ghost windows
     * (`DecoratedWindow(undecorated = true)`). Suppresses caption/border
     * colours and the 1px shadow frame extension so a transparent client has
     * no system contour.
     */
    @JvmStatic
    external fun nativeSetBorderlessChrome(
        hwnd: Long,
        borderless: Boolean,
    )

    /**
     * Applies the whole window theme atomically: `WM_ERASEBKGND` fill, DWM
     * caption/border colors, `DWMWA_USE_IMMERSIVE_DARK_MODE`, and the
     * Windows 10 acrylic tint when that fallback is live. [isDark] arrives
     * resolved (background luminance unless `WindowAppearance` overrides) so
     * there is exactly one resolution point for the entire chrome.
     */
    @JvmStatic
    external fun nativeSetBackgroundColor(
        hwnd: Long,
        argb: Int,
        isDark: Boolean,
    )

    /**
     * Sets the DWM 1px frame colour so the contour follows Win11's rounded
     * HWND clip. [visible] false hides it (maximized / fullscreen).
     */
    @JvmStatic
    external fun nativeSetBorderColor(
        hwnd: Long,
        argb: Int,
        visible: Boolean,
    )

    /**
     * Applies a system backdrop, where [style] is the
     * `DWM_SYSTEMBACKDROP_TYPE` wire value — see
     * [dev.nucleusframework.window.WindowsBackdropStyle]. Degrades across
     * three tiers: `DWMWA_SYSTEMBACKDROP_TYPE` on Windows 11 22H2+,
     * `DWMWA_MICA_EFFECT` on earlier Windows 11, and the
     * `SetWindowCompositionAttribute` acrylic on Windows 10.
     *
     * [tintArgb] is the acrylic tint (Windows 10 tier only, where the material
     * carries no colour of its own); it is ignored unless [hasTint] is true,
     * in which case its alpha decides how much blur survives. The DWM tiers
     * theme themselves and ignore both.
     *
     * Returns the tier actually showing afterwards (1 modern, 2 legacy Mica,
     * 3 Windows 10 accent acrylic), or 0 when none is — including a style
     * this OS cannot honour, which the caller treats as "leave the window
     * opaque". The tier matters because the accent one carries its own tint:
     * the Compose-side tint layer must not double it.
     */
    @JvmStatic
    external fun nativeSetBackdropStyle(
        hwnd: Long,
        style: Int,
        tintArgb: Int,
        hasTint: Boolean,
        tier: Int,
    ): Int

    /**
     * Publishes the client-space rects (physical px) of the Compose-drawn
     * caption buttons as 12 ints — `min(x,y,w,h), max(x,y,w,h),
     * close(x,y,w,h)`, an all-zero quad clearing that slot. `WM_NCHITTEST`
     * answers `HTMINBUTTON`/`HTMAXBUTTON`/`HTCLOSE` over them, which is what
     * makes Windows 11 show the Snap Layouts flyout on maximize hover; the
     * NC clicks these codes generate are forwarded back as client messages,
     * so the buttons stay Compose-handled.
     */
    @JvmStatic
    external fun nativeSetCaptionButtonRects(
        hwnd: Long,
        rects: IntArray,
    )

    /**
     * Reverts an active backdrop to a plain opaque themed window,
     * synchronously. Called from the confirmed destroy path
     * ([TaoWindow.requestClose]) before `DestroyWindow`. Not on cancelable
     * `WM_CLOSE` / [TaoWindow.requestUserClose] — those must leave a live
     * [dev.nucleusframework.window.WindowsBackdrop] intact until destroy is
     * confirmed, otherwise "Cancel" permanently de-mica's the window.
     */
    @JvmStatic
    external fun nativePrepareClose(hwnd: Long)

    /**
     * E2E probe: whether a DWM system backdrop is currently armed on [hwnd]
     * (`DecoState.backdropActive`). Used by the headful suite to assert that a
     * cancelable close request does not permanently tear down Mica/Acrylic.
     */
    @JvmStatic
    external fun nativeIsBackdropActive(hwnd: Long): Boolean

    @JvmStatic
    external fun nativeSetStartupBackgroundEraseEnabled(
        hwnd: Long,
        enabled: Boolean,
    )

    /**
     * Toggles borderless fullscreen. The geometry change runs inline and
     * its `WM_WINDOWPOSCHANGED` re-enters the JVM synchronously via
     * [onFullscreenSizeChanged], so the new-size frame is rendered and
     * presented before this returns — the Windows analog of the macOS
     * `windowWillEnter/ExitFullScreen` prepare (issue 413).
     */
    @JvmStatic
    external fun nativeSetFullscreen(
        hwnd: Long,
        fullscreen: Boolean,
    )

    /**
     * Target client size of the NEXT fullscreen toggle as `[width, height]`
     * physical px (`null` on a would-be no-op). Lets the caller pre-layout
     * at the final size before [nativeSetFullscreen]. Enter is exact; exit
     * is an estimate — the synchronous prepare renders at the real size
     * either way.
     */
    @JvmStatic
    external fun nativeGetFullscreenTargetSize(
        hwnd: Long,
        fullscreen: Boolean,
    ): IntArray?

    // ── Fullscreen transition prepare (Windows) ─────────────────────────
    //
    // The deco WndProc forwards the WM_WINDOWPOSCHANGED generated by the
    // fullscreen toggle's geometry change to [onFullscreenSizeChanged],
    // synchronously, on the Tao main thread. The registered hook renders +
    // presents one frame at that size on the caller's stack — before
    // SetWindowPos returns, hence before DWM composites the new geometry
    // with stale content. Mirrors NativeMetalBridge's fullscreen prepare
    // on macOS (see NucleusTaoMetal.m willEnterFS).

    private val fullscreenSizeHooks = java.util.concurrent.ConcurrentHashMap<Long, (Int, Int) -> Unit>()

    fun setFullscreenSizeHook(
        hwnd: Long,
        block: ((widthPx: Int, heightPx: Int) -> Unit)?,
    ) {
        if (hwnd == 0L) return
        if (block == null) fullscreenSizeHooks.remove(hwnd) else fullscreenSizeHooks[hwnd] = block
    }

    /**
     * Called from native on the Tao main thread, inside the fullscreen
     * toggle's WM_WINDOWPOSCHANGED. Runs on the caller's stack on purpose:
     * the frame has to be presented before the geometry change returns.
     */
    @JvmStatic
    fun onFullscreenSizeChanged(
        hwnd: Long,
        widthPx: Int,
        heightPx: Int,
    ) {
        if (widthPx <= 0 || heightPx <= 0) return
        fullscreenSizeHooks[hwnd]?.invoke(widthPx, heightPx)
    }

    @JvmStatic
    external fun nativeIsFullscreen(hwnd: Long): Boolean

    /**
     * Sets the owner of [childHwnd] to [ownerHwnd] via `GWLP_HWNDPARENT`. The
     * child stays above the owner in z-order, is hidden when the owner is
     * minimised, and does not appear in the taskbar. Pass `0` for [ownerHwnd]
     * to clear the relationship.
     *
     * Used by `DecoratedDialog` to mirror the native owner semantics of an
     * AWT `JDialog`.
     */
    @JvmStatic
    external fun nativeSetOwner(
        childHwnd: Long,
        ownerHwnd: Long,
    )

    /**
     * Returns the window's outer bounds as `[x, y, width, height]` in physical
     * screen pixels, or `null` if the HWND is invalid.
     */
    @JvmStatic
    external fun nativeGetWindowRect(hwnd: Long): LongArray?

    /**
     * Returns the work area (screen minus taskbar) of the monitor hosting [hwnd]
     * as `[x, y, width, height]` in physical pixels. If [hwnd] is 0 or invalid,
     * falls back to the primary monitor.
     */
    @JvmStatic
    external fun nativeOwnerMonitorWorkArea(hwnd: Long): LongArray?

    /**
     * Returns the primary monitor's work area (full screen minus taskbar) as
     * `[x, y, width, height]` in physical pixels. Used to resolve
     * [androidx.compose.ui.window.WindowPosition.Aligned] for the initial
     * outer position of a window.
     */
    @JvmStatic
    external fun nativeGetPrimaryMonitorWorkArea(): LongArray?

    /**
     * Returns one descriptor per attached monitor
     * (`EnumDisplayMonitors` + `GetMonitorInfoW`), encoded as documented in
     * [dev.nucleusframework.window.tao.TaoMonitor]. `null` when the
     * enumeration fails.
     */
    @JvmStatic
    external fun nativeGetMonitors(): Array<String>?

    /**
     * Returns the primary monitor's scale factor encoded as `(scale * 1000)`.
     * Falls back gracefully when `GetDpiForSystem` is unavailable. Used as a
     * scale source while a Tao window's own scale factor is not yet
     * resolvable (the window object exists but the native HWND has not been
     * created yet).
     */
    @JvmStatic
    external fun nativeGetPrimaryMonitorScaleMilli(): Int

    /**
     * Converts a window-client physical-pixel position to screen physical
     * pixels (`ClientToScreen`). Returns `[screenX, screenY]` or `null` on
     * failure. Used by the touch drag path in `TitleBar.titleBarHitTestHandler`
     * to compute window-move deltas — `RegisterTouchWindow` suppresses
     * mouse-message synthesis, so the standard `WM_NCLBUTTONDOWN HTCAPTION`
     * drag loop never fires for touch.
     */
    @JvmStatic
    external fun nativeClientToScreen(
        hwnd: Long,
        xClientPx: Int,
        yClientPx: Int,
    ): IntArray?

    /**
     * Returns true when the cursor is over [hwnd] or an owned Tao popup.
     * Used to ignore the synthetic owner WM_MOUSELEAVE produced when a
     * popup HWND appears under the cursor.
     */
    @JvmStatic
    external fun nativeIsCursorOverWindowOrOwnedPopup(hwnd: Long): Boolean

    /**
     * Synchronous `SetWindowPos(SWP_NOSIZE)`. Used by the Windows touch
     * title-bar drag path — Tao's [TaoWindow.setOuterPosition] posts a user
     * event onto the Tao loop, which lags under a touch stream of
     * 60-100 events/s. Calling `SetWindowPos` directly from the touch-move
     * handler keeps the window pinned to the finger.
     */
    @JvmStatic
    external fun nativeSetWindowOuterPositionPx(
        hwnd: Long,
        xPx: Int,
        yPx: Int,
    )

    /** Win32 `IsZoomed(hwnd)`. */
    @JvmStatic
    external fun nativeIsMaximized(hwnd: Long): Boolean

    /**
     * Atomic unmaximize + reposition under the finger when a touch drag
     * starts on a maximized window. Returns the restored outer rect as
     * `[x, y, w, h]` in physical pixels, or `null` on failure.
     */
    @JvmStatic
    external fun nativePrepareTitleBarTouchDrag(
        hwnd: Long,
        currentScreenX: Int,
        currentScreenY: Int,
        startScreenX: Int,
        startScreenY: Int,
    ): LongArray?
}
