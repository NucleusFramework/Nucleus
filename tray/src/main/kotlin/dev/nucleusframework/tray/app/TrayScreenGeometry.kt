package dev.nucleusframework.tray.app

import dev.nucleusframework.window.tao.TaoScreenGeometry
import dev.nucleusframework.window.tao.TaoWindow

internal data class ScreenRect(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
)

/**
 * Screen geometry backed by the Tao windowing backend. Lives in the tray-app module: it is only
 * needed to place `TrayApp`'s popup window, and the Tao backend is always present here — so there
 * is no "no backend" fallback, the geometry is always real.
 *
 * On Linux the underlying GDK queries need a realized window: `TrayApp` registers its popup window
 * through [taoWindowProvider]. Windows/macOS resolve the primary monitor directly.
 */
internal object TrayScreenGeometry {
    @Volatile
    var taoWindowProvider: (() -> TaoWindow?)? = null

    private fun taoWindow(): TaoWindow? = taoWindowProvider?.invoke()

    /** Primary monitor scale factor (1.0 on non-HiDPI displays). */
    fun scale(): Float =
        runCatching { TaoScreenGeometry.primaryMonitorScaleFactor(taoWindow()) }
            .getOrDefault(1f)
            .coerceAtLeast(1f)

    /**
     * Primary monitor work area (screen minus taskbar/menu bar/panel) in logical pixels, top-left
     * origin. Only the rare case where the native bridge itself throws degrades to a common
     * resolution.
     */
    fun workAreaLogical(): ScreenRect {
        val wa = runCatching { TaoScreenGeometry.primaryMonitorWorkAreaPx(taoWindow()) }.getOrNull()
        if (wa == null || wa.size != 4) return ScreenRect(0, 0, FALLBACK_WIDTH, FALLBACK_HEIGHT)
        val s = scale()
        return ScreenRect(
            (wa[0] / s).toInt(),
            (wa[1] / s).toInt(),
            (wa[2] / s).toInt(),
            (wa[3] / s).toInt(),
        )
    }

    private const val FALLBACK_WIDTH = 1920
    private const val FALLBACK_HEIGHT = 1080
}
