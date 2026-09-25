package dev.nucleusframework.screencapture

/**
 * A display that [ScreenCapture] can capture.
 *
 * @property id platform identifier, stable for the session: the GDI device name on Windows
 *   (`\\.\DISPLAY1`), the `CGDirectDisplayID` on macOS, the RandR output name on X11
 *   (`screen` without RandR), `portal` on Wayland.
 * @property name human-readable name (the monitor's name when the platform reports one).
 * @property bounds the display's rectangle in the platform's desktop coordinate space:
 *   physical pixels of the virtual screen on Windows and X11, points of the global display
 *   space on macOS (origin at the primary display's top-left). `null` on Wayland, where the
 *   compositor does not expose it.
 * @property widthPx width of a full capture of this display, in physical pixels; `0` while
 *   unknown (Wayland, before the first capture).
 * @property heightPx height of a full capture of this display, in physical pixels; `0` while
 *   unknown.
 * @property scaleFactor physical pixels per [bounds] unit on macOS, the display's DPI scale
 *   (`dpi / 96`) on Windows, `1` on X11 and Wayland.
 * @property isPrimary whether this is the primary display.
 */
public class CaptureDisplay internal constructor(
    public val id: String,
    public val name: String,
    public val bounds: CaptureRegion?,
    public val widthPx: Int,
    public val heightPx: Int,
    public val scaleFactor: Float,
    public val isPrimary: Boolean,
) {
    override fun equals(other: Any?): Boolean =
        other is CaptureDisplay &&
            id == other.id &&
            name == other.name &&
            bounds == other.bounds &&
            widthPx == other.widthPx &&
            heightPx == other.heightPx &&
            scaleFactor == other.scaleFactor &&
            isPrimary == other.isPrimary

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + bounds.hashCode()
        result = 31 * result + widthPx
        result = 31 * result + heightPx
        return result
    }

    override fun toString(): String =
        "CaptureDisplay(id=$id, name=$name, bounds=$bounds, px=${widthPx}x$heightPx, " +
            "scale=$scaleFactor, primary=$isPrimary)"
}

/**
 * An axis-aligned rectangle. In [ScreenCapture.captureDisplay] it is expressed in the
 * captured display's physical pixels, relative to its top-left corner — the coordinates of
 * the image a full capture returns.
 */
public data class CaptureRegion(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
) {
    init {
        require(width > 0 && height > 0) { "Region must not be empty: ${width}x$height" }
    }

    /** Right edge, exclusive. */
    val right: Int get() = x + width

    /** Bottom edge, exclusive. */
    val bottom: Int get() = y + height
}
