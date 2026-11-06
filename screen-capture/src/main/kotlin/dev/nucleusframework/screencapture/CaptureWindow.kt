package dev.nucleusframework.screencapture

/**
 * A top-level window [ScreenCapture.captureWindow] can capture, as listed by
 * [ScreenCapture.windows].
 *
 * @property id what [ScreenCapture.captureWindow] takes: the `HWND` on Windows, the
 *   `CGWindowID` on macOS, the client window's XID on X11.
 * @property title the window's title; empty when it has none. On macOS titles are only
 *   reported with the Screen Recording permission.
 * @property appName the owning application's name as the platform reports it: the process
 *   name on Windows (`chrome`), the app name on macOS (`Google Chrome`), the `WM_CLASS`
 *   class on X11 (`Google-chrome`); empty when unknown.
 * @property pid the owning process, `0` when unknown (an X11 client without `_NET_WM_PID`).
 * @property bounds the window's rectangle in the same desktop space as [CaptureDisplay.bounds]:
 *   physical pixels on Windows (the visible frame, without the invisible resize borders) and
 *   X11 (the client area), points on macOS (title bar included).
 */
public class CaptureWindow internal constructor(
    public val id: Long,
    public val title: String,
    public val appName: String,
    public val pid: Long,
    public val bounds: CaptureRegion,
) {
    override fun equals(other: Any?): Boolean =
        other is CaptureWindow &&
            id == other.id &&
            title == other.title &&
            appName == other.appName &&
            pid == other.pid &&
            bounds == other.bounds

    override fun hashCode(): Int = 31 * id.hashCode() + bounds.hashCode()

    override fun toString(): String = "CaptureWindow(id=$id, title=$title, app=$appName, pid=$pid, bounds=$bounds)"
}
