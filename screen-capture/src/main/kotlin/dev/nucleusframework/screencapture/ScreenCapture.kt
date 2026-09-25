package dev.nucleusframework.screencapture

import dev.nucleusframework.core.runtime.Platform
import dev.nucleusframework.screencapture.internal.DisplayCollector
import dev.nucleusframework.screencapture.internal.NativeCall
import dev.nucleusframework.screencapture.internal.NativeScreenCapture
import dev.nucleusframework.screencapture.internal.RawDisplay
import dev.nucleusframework.screencapture.internal.clip
import dev.nucleusframework.screencapture.internal.permissionOf
import java.util.logging.Level
import java.util.logging.Logger

/** The mechanism [ScreenCapture] captures with on this machine. */
public enum class CaptureBackend {
    /** Windows GDI, rendered in a per-monitor DPI context (physical pixels). */
    Gdi,

    /** macOS 14+ ScreenCaptureKit. */
    ScreenCaptureKit,

    /** macOS before 14: Core Graphics display images. */
    CoreGraphics,

    /** Linux X11 (`XGetImage`, RandR outputs, XFixes cursor). */
    X11,

    /**
     * Linux Wayland: `org.freedesktop.portal.Screenshot`. The compositor captures the whole
     * desktop and may ask the user for permission; display regions are cropped from it.
     */
    XdgDesktopPortal,

    /** No backend: the native library did not load or the platform is not supported. */
    Unavailable,
}

/** Whether the app may capture the screen. */
public enum class CapturePermission {
    /** Capture is allowed. */
    Granted,

    /** The user denied it; on macOS it can only be granted again in System Settings. */
    Denied,

    /** Not asked yet: the first capture (or [ScreenCapture.requestPermission]) prompts. */
    NotDetermined,

    /** The platform has no screen capture permission (Windows, X11). */
    NotRequired,
}

/**
 * Native screen capture, without AWT.
 *
 * Every function blocks until the platform answers — call them off the UI thread. They are
 * safe to call from several threads at once.
 *
 * Images are in physical pixels: a 4K display at 200 % captures as 3840×2160, not
 * the 1920×1080 `java.awt.Robot` returns.
 *
 * ```kotlin
 * val display = ScreenCapture.displays().first { it.isPrimary }
 * val image = ScreenCapture.captureDisplay(display, region = CaptureRegion(0, 0, 800, 600))
 * File("shot.png").writeBytes(image.toPng())
 * ```
 */
public object ScreenCapture {
    private val logger = Logger.getLogger(ScreenCapture::class.java.name)
    private const val PORTAL_DISPLAY_ID = "portal"
    private const val PORTAL_TIMEOUT_MS = 60_000
    private const val LINUX_BACKEND_PROPERTY = "nucleus.screencapture.linuxBackend"

    @Volatile
    private var portalImageSize: Pair<Int, Int>? = null

    /** The backend captures go through; [CaptureBackend.Unavailable] when there is none. */
    public val backend: CaptureBackend by lazy { resolveBackend() }

    /** `true` when [backend] is not [CaptureBackend.Unavailable]. */
    public val isSupported: Boolean get() = backend != CaptureBackend.Unavailable

    /** Whether window capture ([captureWindow]) is available: not on Wayland. */
    public val isWindowCaptureSupported: Boolean
        get() = backend != CaptureBackend.Unavailable && backend != CaptureBackend.XdgDesktopPortal

    /**
     * The displays currently connected, primary first.
     *
     * On Wayland this is a single `portal` display standing for the whole desktop.
     *
     * @throws ScreenCaptureException when the backend cannot enumerate them.
     */
    public fun displays(): List<CaptureDisplay> {
        when (backend) {
            CaptureBackend.Unavailable -> return emptyList()
            CaptureBackend.XdgDesktopPortal -> return listOf(portalDisplay())
            else -> Unit
        }
        val collector = DisplayCollector()
        val call = NativeCall("list displays")
        call.check(NativeScreenCapture.nativeListDisplays(collector, call.message))
        return collector.displays
            .map(RawDisplay::toDisplay)
            .sortedByDescending { it.isPrimary }
    }

    /** The primary display, or `null` when none is reported. */
    public fun primaryDisplay(): CaptureDisplay? = displays().firstOrNull()

    /**
     * Captures [display], or the [region] of it (display pixels, relative to its top-left;
     * clipped to the display).
     *
     * @param includeCursor draw the mouse cursor into the image. Ignored on Wayland, where the
     *   compositor decides.
     * @throws ScreenCaptureException see [ScreenCaptureException.failure].
     */
    public fun captureDisplay(
        display: CaptureDisplay,
        region: CaptureRegion? = null,
        includeCursor: Boolean = false,
    ): ScreenImage {
        requireSupported()
        if (backend == CaptureBackend.XdgDesktopPortal) {
            val image = portalScreenshot()
            return if (region == null) image else image.crop(clip(region, image.width, image.height))
        }
        val call = NativeCall("capture display ${display.id}")
        val pixels =
            NativeScreenCapture.nativeCaptureDisplay(
                display.id,
                region?.x ?: 0,
                region?.y ?: 0,
                region?.width ?: 0,
                region?.height ?: 0,
                includeCursor,
                call.result,
                call.message,
            )
        return call.image(pixels, display.scaleFactor)
    }

    /**
     * Captures a top-level window, including the parts other windows cover, when the platform
     * can: an `HWND` on Windows, a `CGWindowID` (`NSWindow.windowNumber`) on macOS, an XID on
     * X11. Not available on Wayland ([isWindowCaptureSupported]).
     *
     * @throws ScreenCaptureException see [ScreenCaptureException.failure].
     */
    public fun captureWindow(
        windowId: Long,
        includeCursor: Boolean = false,
    ): ScreenImage {
        requireSupported()
        if (!isWindowCaptureSupported) {
            throw ScreenCaptureException(CaptureFailure.Unsupported, "Window capture is not available on $backend")
        }
        val call = NativeCall("capture window $windowId")
        val pixels = NativeScreenCapture.nativeCaptureWindow(windowId, includeCursor, call.result, call.message)
        return call.image(pixels, 1f)
    }

    /** Whether the app may capture the screen, without prompting. */
    public fun permissionStatus(): CapturePermission =
        when (backend) {
            CaptureBackend.Unavailable -> CapturePermission.Denied
            CaptureBackend.ScreenCaptureKit, CaptureBackend.CoreGraphics ->
                permissionOf(NativeScreenCapture.nativePermissionStatus())
            else -> CapturePermission.NotRequired
        }

    /**
     * Asks for screen recording permission where the platform has one (macOS: the system
     * prompt, shown once per app; the answer takes effect after the app restarts). Returns the
     * resulting status without waiting for the user.
     */
    public fun requestPermission(): CapturePermission =
        when (backend) {
            CaptureBackend.ScreenCaptureKit, CaptureBackend.CoreGraphics ->
                permissionOf(NativeScreenCapture.nativeRequestPermission())
            else -> permissionStatus()
        }

    private fun resolveBackend(): CaptureBackend {
        if (Platform.Current == Platform.Unknown) return CaptureBackend.Unavailable
        val loaded =
            try {
                NativeScreenCapture.isLoaded
            } catch (e: LinkageError) {
                logger.log(Level.WARNING, "Screen capture native library failed to load", e)
                false
            }
        if (!loaded) {
            logger.warning("Screen capture native library is not available on ${Platform.Current}")
            return CaptureBackend.Unavailable
        }
        if (Platform.Current == Platform.Linux && useLinuxPortal()) return CaptureBackend.XdgDesktopPortal
        return when (NativeScreenCapture.nativeBackend()) {
            NativeScreenCapture.BACKEND_GDI -> CaptureBackend.Gdi
            NativeScreenCapture.BACKEND_SCREEN_CAPTURE_KIT -> CaptureBackend.ScreenCaptureKit
            NativeScreenCapture.BACKEND_CORE_GRAPHICS -> CaptureBackend.CoreGraphics
            NativeScreenCapture.BACKEND_X11 -> CaptureBackend.X11
            // Linux without an X server may still have a portal.
            else ->
                if (Platform.Current ==
                    Platform.Linux
                ) {
                    CaptureBackend.XdgDesktopPortal
                } else {
                    CaptureBackend.Unavailable
                }
        }
    }

    /**
     * On a Wayland session the X server is XWayland, whose root window does not hold the
     * native Wayland windows — only the portal sees the real desktop.
     */
    private fun useLinuxPortal(): Boolean =
        when (System.getProperty(LINUX_BACKEND_PROPERTY)?.lowercase()) {
            "x11" -> false
            "portal" -> true
            else -> Platform.isWayland
        }

    private fun portalDisplay(): CaptureDisplay {
        val size = portalImageSize
        return CaptureDisplay(
            id = PORTAL_DISPLAY_ID,
            name = "Desktop",
            bounds = null,
            widthPx = size?.first ?: 0,
            heightPx = size?.second ?: 0,
            scaleFactor = 1f,
            isPrimary = true,
        )
    }

    private fun portalScreenshot(): ScreenImage {
        val call = NativeCall("take a portal screenshot")
        val pixels = NativeScreenCapture.nativePortalScreenshot(false, PORTAL_TIMEOUT_MS, call.result, call.message)
        val image = call.image(pixels, 1f)
        portalImageSize = image.width to image.height
        return image
    }

    private fun requireSupported() {
        if (backend == CaptureBackend.Unavailable) {
            throw ScreenCaptureException(CaptureFailure.Unsupported, "No screen capture backend on ${Platform.Current}")
        }
    }
}

private fun RawDisplay.toDisplay(): CaptureDisplay =
    CaptureDisplay(
        id = id,
        name = name,
        bounds = CaptureRegion(x, y, width.coerceAtLeast(1), height.coerceAtLeast(1)),
        widthPx = widthPx,
        heightPx = heightPx,
        scaleFactor = scaleFactor,
        isPrimary = isPrimary,
    )
