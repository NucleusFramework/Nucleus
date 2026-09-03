package dev.nucleusframework.window.tao

import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp
import dev.nucleusframework.core.runtime.Platform
import dev.nucleusframework.window.tao.ffi.NativeTaoBridge
import dev.nucleusframework.window.tao.ffi.NativeTaoMacOsDecoBridge
import dev.nucleusframework.window.tao.ffi.NativeTaoWindowsDecoBridge

// Wire format: id, name, then 4 bounds + 4 work-area numbers, scaleMilli, primary.
private const val FIELD_ID = 0
private const val FIELD_NAME = 1
private const val FIRST_NUMERIC_FIELD = 2

/** 4 bounds + 4 work-area numbers + scaleMilli; `primary` is read as a flag. */
private const val NUMERIC_FIELD_COUNT = 9
private const val SCALE_MILLI_INDEX = 8
private const val FIELD_PRIMARY = 11
private const val MONITOR_FIELD_COUNT = 12

// Indices into an [x, y, width, height] rectangle, native or wire.
private const val RECT_X = 0
private const val RECT_Y = 1
private const val RECT_WIDTH = 2
private const val RECT_HEIGHT = 3
private const val RECT_LENGTH = 4

private const val SCALE_MILLI = 1000f
private const val FALLBACK_WIDTH_PX = 1920
private const val FALLBACK_HEIGHT_PX = 1080

/**
 * A display attached to the machine, as reported by the platform's own monitor
 * enumeration — `EnumDisplayMonitors` on Windows, `NSScreen.screens` on macOS,
 * GDK monitors on Linux.
 *
 * This is the no-AWT counterpart of `java.awt.GraphicsDevice`: the Tao backend
 * never initializes the AWT toolkit, so `GraphicsEnvironment` is not an option
 * (and would report a DPI-scaled coordinate space that does not match Tao's
 * physical pixels on mixed-DPI Windows setups).
 *
 * ### Native wire format
 *
 * Every platform bridge encodes one monitor per tab-separated string, so a
 * single JNI call carries the whole enumeration:
 *
 * ```
 * id \t name \t x \t y \t width \t height \t
 *   workX \t workY \t workWidth \t workHeight \t scaleMilli \t primary
 * ```
 *
 * Geometry is **physical pixels with a top-left origin** in the global
 * multi-monitor space, matching [TaoWindow.outerBoundsPx]. `scaleMilli` is the
 * scale factor times 1000 and `primary` is `1` or `0`.
 */
public class TaoMonitor internal constructor(
    /**
     * Platform identifier, stable for as long as the monitor stays attached:
     * the GDI device name on Windows (`\\.\DISPLAY1`), `display-<CGDirectDisplayID>`
     * on macOS, the EDID model (or `monitor-<index>`) on Linux.
     */
    public val id: String,
    /** Human-readable display name, for a monitor picker UI. */
    public val name: String,
    /** Full monitor rectangle in physical pixels. */
    public val boundsPx: IntRect,
    /** Monitor rectangle minus taskbar / menu bar / dock / panels, in physical pixels. */
    public val workAreaPx: IntRect,
    /** The monitor's own scale factor (`1.0` on non-HiDPI displays). */
    public val scaleFactor: Float,
    /**
     * Whether this is the primary monitor — the one owning the origin.
     *
     * Exactly one monitor of [TaoMonitors.all] carries it: where the platform
     * names no primary (GDK's Wayland backend does not), the first monitor is
     * flagged, so filtering the list by this always finds one.
     */
    public val isPrimary: Boolean,
) {
    /**
     * [boundsPx] converted to density-independent pixels.
     *
     * [scale] defaults to the monitor's own [scaleFactor], which is the right
     * answer for a single-monitor or uniform-DPI setup. Pass the scale of the
     * window being positioned when the result feeds window geometry: Tao's
     * window coordinates are physical pixels divided by *one* scale, so mixing
     * per-monitor scales would misplace windows on mixed-DPI setups.
     */
    public fun boundsDp(scale: Float = scaleFactor): DpRect = boundsPx.toDpRect(scale)

    /** [workAreaPx] converted to density-independent pixels. See [boundsDp]. */
    public fun workAreaDp(scale: Float = scaleFactor): DpRect = workAreaPx.toDpRect(scale)

    /** Whether [xPx] / [yPx] (physical pixels) fall inside [boundsPx]. */
    public fun containsPx(
        xPx: Int,
        yPx: Int,
    ): Boolean = xPx >= boundsPx.left && xPx < boundsPx.right && yPx >= boundsPx.top && yPx < boundsPx.bottom

    override fun equals(other: Any?): Boolean = this === other || (other is TaoMonitor && other.id == id)

    override fun hashCode(): Int = id.hashCode()

    override fun toString(): String = "TaoMonitor($id, $name, $boundsPx, scale=$scaleFactor, primary=$isPrimary)"
}

/**
 * Multi-monitor enumeration for the Tao backend.
 *
 * The AWT-free counterpart of `GraphicsEnvironment.getScreenDevices()`, and the
 * data source behind [dev.nucleusframework.window.tao.v2.Screen].
 *
 * Queries hit the platform bridge on every call rather than caching: monitors
 * come and go (a laptop docking, a projector unplugged) and the underlying
 * calls are cheap. [all] never returns an empty list — without a platform
 * bridge it synthesizes one monitor from [TaoScreenGeometry] so a screen picker
 * always has something to show.
 */
public object TaoMonitors {
    /**
     * Every attached monitor, primary first on macOS and in platform order
     * elsewhere.
     *
     * [window] is only used on Linux, where GDK resolves monitors through a
     * display reachable from a realized window; `null` falls back to the
     * default GDK display. Ignored on Windows and macOS.
     */
    public fun all(window: TaoWindow? = null): List<TaoMonitor> {
        val rows =
            when (Platform.Current) {
                Platform.Windows ->
                    if (NativeTaoWindowsDecoBridge.isLoaded) NativeTaoWindowsDecoBridge.nativeGetMonitors() else null
                Platform.MacOS ->
                    if (NativeTaoMacOsDecoBridge.isLoaded) NativeTaoMacOsDecoBridge.nativeGetMonitors() else null
                Platform.Linux ->
                    if (NativeTaoBridge.isLoaded) NativeTaoBridge.nativeLinuxMonitors(window?.handle ?: 0L) else null
                else -> null
            }
        val monitors = rows?.mapNotNull(::parseMonitor).orEmpty()
        return monitors.ifEmpty { listOf(syntheticMonitor(window)) }.withOnePrimary()
    }

    /**
     * Exactly one monitor carrying [TaoMonitor.isPrimary]: the one the platform
     * named, else the first.
     *
     * Not every platform names one — GDK's Wayland backend reports no primary
     * monitor at all — and a list where the flag is nowhere makes
     * `all().first { it.isPrimary }` throw for a caller doing the obvious
     * thing. The fallback is the same one [primary] already applies; applying
     * it here makes the flag mean something on every platform.
     */
    private fun List<TaoMonitor>.withOnePrimary(): List<TaoMonitor> {
        if (any { it.isPrimary }) return this
        val chosen = first()
        return listOf(
            TaoMonitor(
                id = chosen.id,
                name = chosen.name,
                boundsPx = chosen.boundsPx,
                workAreaPx = chosen.workAreaPx,
                scaleFactor = chosen.scaleFactor,
                isPrimary = true,
            ),
        ) + drop(1)
    }

    /** The primary monitor — see [TaoMonitor.isPrimary]. */
    public fun primary(window: TaoWindow? = null): TaoMonitor {
        val monitors = all(window)
        return monitors.firstOrNull { it.isPrimary } ?: monitors.first()
    }

    /** The monitor with the given [id], or `null` when it is no longer attached. */
    public fun byId(
        id: String,
        window: TaoWindow? = null,
    ): TaoMonitor? = all(window).firstOrNull { it.id == id }

    /**
     * The monitor hosting [window] — the one containing the centre of its outer
     * rectangle, falling back to the largest-overlap monitor and finally to
     * [primary] (which also covers a window that is not realized yet).
     */
    public fun forWindow(window: TaoWindow?): TaoMonitor {
        val monitors = all(window)
        val rect = window?.outerBoundsPx()?.takeIf { it.size == RECT_LENGTH } ?: return primary(window)
        val left = rect[RECT_X].toInt()
        val top = rect[RECT_Y].toInt()
        val width = rect[RECT_WIDTH].toInt()
        val height = rect[RECT_HEIGHT].toInt()
        val centreX = left + width / 2
        val centreY = top + height / 2
        monitors.firstOrNull { it.containsPx(centreX, centreY) }?.let { return it }
        val bounds = IntRect(left, top, left + width, top + height)
        return monitors.maxByOrNull { overlapArea(it.boundsPx, bounds) }
            ?: primary(window)
    }

    /**
     * The scale factor to interpret window geometry with: the window's own when
     * it is realized, otherwise its monitor's.
     *
     * Every Dp rectangle the window API produces has to share one scale — see
     * [TaoMonitor.boundsDp].
     */
    internal fun referenceScale(window: TaoWindow?): Float {
        val windowScale = window?.scaleFactor ?: 0f
        if (windowScale > 0f) return windowScale
        return primary(window).scaleFactor
    }

    private fun overlapArea(
        a: IntRect,
        b: IntRect,
    ): Long {
        val width = (minOf(a.right, b.right) - maxOf(a.left, b.left)).coerceAtLeast(0)
        val height = (minOf(a.bottom, b.bottom) - maxOf(a.top, b.top)).coerceAtLeast(0)
        return width.toLong() * height.toLong()
    }

    /**
     * Single monitor derived from the primary work area, for a runtime without
     * the platform bridge (or a headless CI box). The work area doubles as the
     * full bounds — the taskbar inset is unknowable here.
     */
    private fun syntheticMonitor(window: TaoWindow?): TaoMonitor {
        val work = TaoScreenGeometry.primaryMonitorWorkAreaPx(window)?.takeIf { it.size == RECT_LENGTH }
        val scale = TaoScreenGeometry.primaryMonitorScaleFactor(window)
        val rect =
            if (work != null) {
                IntRect(
                    left = work[RECT_X].toInt(),
                    top = work[RECT_Y].toInt(),
                    right = (work[RECT_X] + work[RECT_WIDTH]).toInt(),
                    bottom = (work[RECT_Y] + work[RECT_HEIGHT]).toInt(),
                )
            } else {
                IntRect(0, 0, (FALLBACK_WIDTH_PX * scale).toInt(), (FALLBACK_HEIGHT_PX * scale).toInt())
            }
        return TaoMonitor(
            id = "primary",
            name = "Primary",
            boundsPx = rect,
            workAreaPx = rect,
            scaleFactor = scale,
            isPrimary = true,
        )
    }

    internal fun parseMonitor(row: String): TaoMonitor? {
        val fields = row.split('\t')
        if (fields.size != MONITOR_FIELD_COUNT) return null
        val id = fields[FIELD_ID]
        val name = fields[FIELD_NAME]
        val numbers = IntArray(NUMERIC_FIELD_COUNT)
        for (index in numbers.indices) {
            numbers[index] = fields[FIRST_NUMERIC_FIELD + index].toIntOrNull() ?: return null
        }
        val bounds = rectOrNull(numbers, offset = 0) ?: return null
        val scale = (numbers[SCALE_MILLI_INDEX] / SCALE_MILLI).takeIf { it > 0f } ?: 1f
        return TaoMonitor(
            id = id.ifEmpty { "monitor" },
            name = name.ifEmpty { id },
            boundsPx = bounds,
            // Some Wayland compositors report no work area; the full monitor is
            // the honest answer there, not a zero-sized rectangle.
            workAreaPx = rectOrNull(numbers, offset = RECT_LENGTH) ?: bounds,
            scaleFactor = scale,
            isPrimary = fields[FIELD_PRIMARY] == "1",
        )
    }

    /** `[x, y, width, height]` at [offset], or `null` when the size is empty. */
    private fun rectOrNull(
        numbers: IntArray,
        offset: Int,
    ): IntRect? {
        val x = numbers[offset + RECT_X]
        val y = numbers[offset + RECT_Y]
        val width = numbers[offset + RECT_WIDTH]
        val height = numbers[offset + RECT_HEIGHT]
        if (width <= 0 || height <= 0) return null
        return IntRect(left = x, top = y, right = x + width, bottom = y + height)
    }
}

private fun IntRect.toDpRect(scale: Float): DpRect {
    val safeScale = if (scale > 0f) scale else 1f
    return DpRect(
        left = (left / safeScale).dp,
        top = (top / safeScale).dp,
        right = (right / safeScale).dp,
        bottom = (bottom / safeScale).dp,
    )
}
