package dev.nucleusframework.window.tao.ffi

import dev.nucleusframework.core.runtime.NativeLibraryLoader
import java.util.concurrent.ConcurrentHashMap

private const val LIBRARY_NAME = "nucleus_tao_windows_deco"

/**
 * JNI bridge to the WndProc subclass that gives a Tao HWND a custom title bar
 * (client-area extension via `WM_NCCALCSIZE`, hit-test routing via
 * `WM_NCHITTEST`, DWM shadow via `DwmExtendFrameIntoClientArea`).
 *
 * Mirrors the API of `decorated-window-jni`'s `JniWindowsDecorationBridge`,
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

    /** ARGB; updates the WM_ERASEBKGND fill, DWM caption/border colors, and
     * dark-mode flag based on luminance. */
    @JvmStatic
    external fun nativeSetBackgroundColor(
        hwnd: Long,
        argb: Int,
    )

    @JvmStatic
    external fun nativeSetStartupBackgroundEraseEnabled(
        hwnd: Long,
        enabled: Boolean,
    )

    @JvmStatic
    external fun nativeSetFullscreen(
        hwnd: Long,
        fullscreen: Boolean,
    )

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
     * Returns the primary monitor's work area (full screen minus taskbar) as
     * `[x, y, width, height]` in physical pixels. Used to resolve
     * [androidx.compose.ui.window.WindowPosition.Aligned] for the initial
     * outer position of a window.
     */
    @JvmStatic
    external fun nativeGetPrimaryMonitorWorkArea(): LongArray?

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

    /**
     * A Compose-drawn caption button that the WndProc reports to Windows as a
     * real one. The ordinals are the wire format shared with
     * `CAPTION_BUTTON_*` in `nucleus_tao_windows_deco.c`.
     */
    internal enum class CaptionButton {
        Minimize,
        Maximize,
        Close,
    }

    /**
     * Reports a caption button's bounds, in client physical pixels, so
     * `WM_NCHITTEST` answers `HTMINBUTTON`/`HTMAXBUTTON`/`HTCLOSE` there.
     * That is what gives the Compose-drawn buttons the native affordances:
     * system tooltips on all three, and the Windows 11 Snap Layouts flyout on
     * maximize.
     *
     * Pass an empty rect (all zeros) to remove the zone when the button is not
     * on screen.
     */
    @JvmStatic
    external fun nativeSetCaptionButtonBounds(
        hwnd: Long,
        button: Int,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
    )

    /**
     * Receives the caption buttons' interaction stream. Hit-testing them as
     * caption buttons moves their mouse input into the non-client stream, so
     * Compose no longer sees enter/exit/press over them and the WndProc feeds
     * the state back instead.
     *
     * Callbacks run on the Tao event-loop thread, which is the Compose UI
     * thread — implementations may write snapshot state directly.
     */
    internal interface CaptionButtonListener {
        /** [hot] and [pressed] are `null` when no button is hot/pressed. */
        fun onStateChanged(
            hot: CaptionButton?,
            pressed: CaptionButton?,
        )

        fun onClick(button: CaptionButton)
    }

    private val captionButtonListeners = ConcurrentHashMap<Long, CaptionButtonListener>()

    fun setCaptionButtonListener(
        hwnd: Long,
        listener: CaptionButtonListener?,
    ) {
        if (listener == null) {
            captionButtonListeners.remove(hwnd)
        } else {
            captionButtonListeners[hwnd] = listener
        }
    }

    private fun captionButtonOf(ordinal: Int): CaptionButton? = CaptionButton.entries.getOrNull(ordinal)

    // Called from decoWndProc — keep the names and signatures in sync with
    // `ensureCallbackCache` in nucleus_tao_windows_deco.c.
    @Suppress("unused")
    @JvmStatic
    private fun onCaptionButtonState(
        hwnd: Long,
        hot: Int,
        pressed: Int,
    ) {
        captionButtonListeners[hwnd]?.onStateChanged(captionButtonOf(hot), captionButtonOf(pressed))
    }

    @Suppress("unused")
    @JvmStatic
    private fun onCaptionButtonClick(
        hwnd: Long,
        button: Int,
    ) {
        val resolved = captionButtonOf(button) ?: return
        captionButtonListeners[hwnd]?.onClick(resolved)
    }
}
