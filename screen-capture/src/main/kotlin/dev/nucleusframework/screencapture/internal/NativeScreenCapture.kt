package dev.nucleusframework.screencapture.internal

import dev.nucleusframework.core.runtime.NativeLibraryLoader

/**
 * JNI bridge, one library per platform with the same symbols.
 *
 * Capture calls return the pixels (`0xAARRGGBB`, row-major, alpha forced opaque) or `null`,
 * and fill `result` with `[status, width, height]`; `message[0]` receives a diagnostic on
 * failure. Status codes are the `STATUS_*` constants.
 */
internal object NativeScreenCapture {
    const val STATUS_OK = 0
    const val STATUS_UNSUPPORTED = 1
    const val STATUS_PERMISSION_DENIED = 2
    const val STATUS_DISPLAY_NOT_FOUND = 3
    const val STATUS_WINDOW_NOT_FOUND = 4
    const val STATUS_CANCELLED = 5
    const val STATUS_FAILED = 6
    const val STATUS_TIMEOUT = 7
    const val STATUS_INVALID_REGION = 8

    const val PERMISSION_GRANTED = 0
    const val PERMISSION_DENIED = 1
    const val PERMISSION_NOT_DETERMINED = 2
    const val PERMISSION_NOT_REQUIRED = 3

    // nativeBackend() values.
    const val BACKEND_NONE = 0
    const val BACKEND_GDI = 1
    const val BACKEND_SCREEN_CAPTURE_KIT = 2
    const val BACKEND_CORE_GRAPHICS = 3
    const val BACKEND_X11 = 4

    val isLoaded: Boolean by lazy { NativeLibraryLoader.load("nucleus_screencapture", NativeScreenCapture::class.java) }

    /** The backend the native side uses for display capture; [BACKEND_NONE] when it has none. */
    @JvmStatic
    external fun nativeBackend(): Int

    /** Reports every display to [sink]; returns a status code. */
    @JvmStatic
    external fun nativeListDisplays(
        sink: DisplayCollector,
        message: Array<String?>,
    ): Int

    /**
     * Captures [displayId]. A region with `width <= 0` means the whole display; otherwise it is
     * in display pixels and the native side clips it to the display.
     */
    @JvmStatic
    external fun nativeCaptureDisplay(
        displayId: String,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        includeCursor: Boolean,
        result: IntArray,
        message: Array<String?>,
    ): IntArray?

    /** Captures a top-level window: an `HWND` on Windows, a `CGWindowID` on macOS, an XID on X11. */
    @JvmStatic
    external fun nativeCaptureWindow(
        windowId: Long,
        includeCursor: Boolean,
        result: IntArray,
        message: Array<String?>,
    ): IntArray?

    @JvmStatic
    external fun nativePermissionStatus(): Int

    @JvmStatic
    external fun nativeRequestPermission(): Int

    /** Linux only: `org.freedesktop.portal.Screenshot`, decoded to pixels. */
    @JvmStatic
    external fun nativePortalScreenshot(
        interactive: Boolean,
        timeoutMs: Int,
        result: IntArray,
        message: Array<String?>,
    ): IntArray?
}

/** Receives [NativeScreenCapture.nativeListDisplays] records; called from native code. */
internal class DisplayCollector {
    val displays = mutableListOf<RawDisplay>()

    @Suppress("LongParameterList")
    fun add(
        id: String,
        name: String,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        widthPx: Int,
        heightPx: Int,
        scaleFactor: Float,
        isPrimary: Boolean,
    ) {
        displays += RawDisplay(id, name, x, y, width, height, widthPx, heightPx, scaleFactor, isPrimary)
    }
}

internal data class RawDisplay(
    val id: String,
    val name: String,
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
    val widthPx: Int,
    val heightPx: Int,
    val scaleFactor: Float,
    val isPrimary: Boolean,
)
