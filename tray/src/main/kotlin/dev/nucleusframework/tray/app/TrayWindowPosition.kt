package dev.nucleusframework.tray.app

import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPosition
import dev.nucleusframework.core.runtime.Platform
import dev.nucleusframework.tray.internal.MacTrayInitializer
import dev.nucleusframework.tray.internal.WindowsTrayInitializer
import dev.nucleusframework.tray.utils.AppIdProvider
import dev.nucleusframework.tray.utils.TrayClickPoint
import dev.nucleusframework.tray.utils.TrayClickTracker
import dev.nucleusframework.tray.utils.debugln
import java.io.File
import java.util.Properties
import kotlin.math.roundToInt

// Popup positioning for TrayApp. Lives in the tray-app module because it needs screen geometry
// (Tao) which the core artifact deliberately doesn't depend on. The native tray managers only
// record the raw click coordinate in TrayClickTracker; everything below turns that into a window
// position against the real Tao-backed screen geometry.

private data class ResolvedClick(
    val x: Int,
    val y: Int,
    val corner: TrayPosition,
)

/** Windows records the click in physical pixels; convert to logical and derive the corner. */
private fun resolveWindowsClick(raw: TrayClickPoint): ResolvedClick {
    val scale = TrayScreenGeometry.scale()
    val logicalX = (raw.x / scale).toInt()
    val logicalY = (raw.y / scale).toInt()
    val screen = TrayScreenGeometry.workAreaLogical()
    val corner = convertPositionToCorner(logicalX - screen.x, logicalY - screen.y, screen.width, screen.height)
    return ResolvedClick(logicalX, logicalY, corner)
}

/**
 * Origin (logical px) of a [width] x [height] popup anchored to a Windows tray icon. Pure, so every
 * DPI scale and taskbar edge can be checked without a matching machine.
 *
 * @param rawX icon anchor from the native side, physical px: the icon's center x, and its bottom
 *   edge when the icon sits in the top half of its monitor, its top edge otherwise
 * @param scale primary monitor scale (physical / logical)
 * @param workArea primary monitor work area, logical px
 */
internal fun windowsPopupOrigin(
    rawX: Int,
    rawY: Int,
    scale: Float,
    workArea: ScreenRect,
    width: Int,
    height: Int,
    horizontalOffset: Int,
    verticalOffset: Int,
): Pair<Int, Int> {
    val clickX = (rawX / scale).toInt()
    val clickY = (rawY / scale).toInt()
    val corner = convertPositionToCorner(clickX - workArea.x, clickY - workArea.y, workArea.width, workArea.height)
    val isTop = corner == TrayPosition.TOP_LEFT || corner == TrayPosition.TOP_RIGHT
    val x = clickX - width / 2 + horizontalOffset
    val y = (if (isTop) clickY else clickY - height) + verticalOffset
    val position = clampToWorkArea(x, y, width, height, workArea) as WindowPosition.Absolute
    return position.x.value.toInt() to position.y.value.toInt()
}

/** Linux/macOS record the click in logical pixels already; derive the corner. */
private fun resolveLogicalClick(raw: TrayClickPoint): ResolvedClick {
    val screen = TrayScreenGeometry.workAreaLogical()
    val corner = convertPositionToCorner(raw.x - screen.x, raw.y - screen.y, screen.width, screen.height)
    return ResolvedClick(raw.x, raw.y, corner)
}

/** Global (instance-less) tray-anchored window position + offsets. */
public fun getTrayWindowPosition(
    windowWidth: Int,
    windowHeight: Int,
    horizontalOffset: Int = 0,
    verticalOffset: Int = 0,
): WindowPosition {
    val os = Platform.Current

    if (os == Platform.Windows) {
        val resolved =
            TrayClickTracker.lastClick()?.let { resolveWindowsClick(it) }?.also { savePersistedClick(it) }
                ?: loadPersistedClick()
        if (resolved == null) {
            val corner = getTrayPosition()
            val screen = TrayScreenGeometry.workAreaLogical()
            val (sx, sy) = syntheticClickFromCorner(corner, screen)
            return calculateWindowPositionFromClick(
                sx,
                sy,
                corner,
                windowWidth,
                windowHeight,
                horizontalOffset,
                verticalOffset,
            )
        }
        return calculateWindowPositionFromClick(
            resolved.x,
            resolved.y,
            resolved.corner,
            windowWidth,
            windowHeight,
            horizontalOffset,
            verticalOffset,
        )
    }

    if (os == Platform.MacOS) {
        val outXY = IntArray(2)
        if (MacTrayInitializer.statusItemPosition(outXY)) {
            val screen = TrayScreenGeometry.workAreaLogical()
            if (isLaidOutMacStatusItem(outXY[0], outXY[1], screen)) {
                val corner = getTrayPosition()
                return calculateWindowPositionFromClick(
                    outXY[0],
                    outXY[1],
                    corner,
                    windowWidth,
                    windowHeight,
                    horizontalOffset,
                    verticalOffset,
                )
            }
        }
    }

    if (os == Platform.Linux) {
        val resolved =
            TrayClickTracker.lastClick()?.let { resolveLogicalClick(it) }?.also { savePersistedClick(it) }
                ?: loadPersistedClick()
        if (resolved != null) {
            return calculateWindowPositionFromClick(
                resolved.x,
                resolved.y,
                resolved.corner,
                windowWidth,
                windowHeight,
                horizontalOffset,
                verticalOffset,
            )
        }
    }

    return fallbackCornerPosition(windowWidth, windowHeight, horizontalOffset, verticalOffset)
}

/** Per-instance variant (Windows multi-tray, macOS precise status-item) + offsets. */
public fun getTrayWindowPositionForInstance(
    instanceId: String,
    windowWidth: Int,
    windowHeight: Int,
    horizontalOffset: Int = 0,
    verticalOffset: Int = 0,
): WindowPosition {
    return when (Platform.Current) {
        Platform.Windows -> {
            // No click yet (a programmatic show, or a tray just recreated): ask the shell now
            // rather than falling back to a corner guess.
            val raw =
                TrayClickTracker.lastClick(instanceId)
                    ?: run {
                        WindowsTrayInitializer.refreshPosition(instanceId)
                        TrayClickTracker.lastClick(instanceId)
                    }
            if (raw == null) {
                debugln {
                    "[TrayPosition] getTrayWindowPositionForInstance: no position for $instanceId, using fallback"
                }
                return fallbackCornerPosition(windowWidth, windowHeight, horizontalOffset, verticalOffset)
            }
            val (x, y) =
                windowsPopupOrigin(
                    raw.x,
                    raw.y,
                    TrayScreenGeometry.scale(),
                    TrayScreenGeometry.workAreaLogical(),
                    windowWidth,
                    windowHeight,
                    horizontalOffset,
                    verticalOffset,
                )
            WindowPosition(x.dp, y.dp)
        }
        Platform.MacOS -> {
            val outXY = IntArray(2)
            if (MacTrayInitializer.statusItemPositionFor(instanceId, outXY)) {
                val x = outXY[0]
                val y = outXY[1]
                val bounds = getScreenBoundsAt(x, y)
                if (!isLaidOutMacStatusItem(x, y, bounds)) {
                    debugln {
                        "[TrayPosition] mac instance $instanceId unlaid-out ($x,$y), PlatformDefault"
                    }
                    return WindowPosition.PlatformDefault
                }
                val regionStr = MacTrayInitializer.statusItemRegionFor(instanceId)
                val trayPos =
                    if (regionStr != null) {
                        getMacTrayPosition(regionStr)
                    } else {
                        convertPositionToCorner(x - bounds.x, y - bounds.y, bounds.width, bounds.height)
                    }
                return calculateWindowPositionFromClick(
                    x,
                    y,
                    trayPos,
                    windowWidth,
                    windowHeight,
                    horizontalOffset,
                    verticalOffset,
                )
            }
            // Status item not realised yet (async init). Report PlatformDefault so pollers retry
            // rather than latching a corner fallback.
            debugln { "[TrayPosition] mac instance $instanceId not ready, PlatformDefault" }
            WindowPosition.PlatformDefault
        }
        else -> getTrayWindowPosition(windowWidth, windowHeight, horizontalOffset, verticalOffset)
    }
}

internal fun convertPositionToCorner(
    x: Int,
    y: Int,
    width: Int,
    height: Int,
): TrayPosition {
    val edgeThreshold = 100

    val isNearTop = y < edgeThreshold
    val isNearBottom = y > height - edgeThreshold
    val isNearLeft = x < edgeThreshold
    val isNearRight = x > width - edgeThreshold

    return when {
        isNearTop && isNearLeft -> TrayPosition.TOP_LEFT
        isNearTop && isNearRight -> TrayPosition.TOP_RIGHT
        isNearTop -> TrayPosition.TOP_RIGHT
        isNearBottom && isNearLeft -> TrayPosition.BOTTOM_LEFT
        isNearBottom && isNearRight -> TrayPosition.BOTTOM_RIGHT
        isNearBottom -> TrayPosition.BOTTOM_RIGHT
        x >= width / 2 && y < height / 2 -> TrayPosition.TOP_RIGHT
        x < width / 2 && y < height / 2 -> TrayPosition.TOP_LEFT
        x >= width / 2 -> TrayPosition.BOTTOM_RIGHT
        else -> TrayPosition.BOTTOM_LEFT
    }
}

@Suppress("UNUSED_PARAMETER")
private fun getScreenBoundsAt(
    x: Int,
    y: Int,
): ScreenRect = TrayScreenGeometry.workAreaLogical()

/**
 * Computes the (x,y) window origin from a precise click, applies offsets and clamps to screen edges.
 * Coordinates are logical pixels within the primary monitor's work area.
 */
private fun calculateWindowPositionFromClick(
    clickX: Int,
    clickY: Int,
    trayPosition: TrayPosition,
    windowWidth: Int,
    windowHeight: Int,
    horizontalOffset: Int,
    verticalOffset: Int,
): WindowPosition {
    val os = Platform.Current
    val isTop = trayPosition == TrayPosition.TOP_LEFT || trayPosition == TrayPosition.TOP_RIGHT
    val isRight = trayPosition == TrayPosition.TOP_RIGHT || trayPosition == TrayPosition.BOTTOM_RIGHT

    val sb = getScreenBoundsAt(clickX, clickY)

    return if (os == Platform.Windows) {
        var x = clickX - (windowWidth / 2)
        var y = if (isTop) clickY else clickY - windowHeight

        x += horizontalOffset
        y += verticalOffset

        clampToWorkArea(x, y, windowWidth, windowHeight, sb)
    } else {
        var x = clickX - (windowWidth / 2)
        val anchorY = if (isTop) sb.y else (sb.y + sb.height)
        var y = if (isTop) anchorY else anchorY - windowHeight

        x += if (isRight) -horizontalOffset else horizontalOffset
        y += if (isTop) verticalOffset else -verticalOffset

        clampToWorkArea(x, y, windowWidth, windowHeight, sb)
    }
}

private fun fallbackCornerPosition(
    w: Int,
    h: Int,
    horizontalOffset: Int,
    verticalOffset: Int,
): WindowPosition {
    val screen = TrayScreenGeometry.workAreaLogical()
    return when (getTrayPosition()) {
        TrayPosition.TOP_LEFT ->
            clampToWorkArea(screen.x + horizontalOffset, screen.y + verticalOffset, w, h, screen)
        TrayPosition.TOP_RIGHT ->
            clampToWorkArea(screen.x + screen.width - w + horizontalOffset, screen.y + verticalOffset, w, h, screen)
        TrayPosition.BOTTOM_LEFT ->
            clampToWorkArea(screen.x + horizontalOffset, screen.y + screen.height - h + verticalOffset, w, h, screen)
        TrayPosition.BOTTOM_RIGHT ->
            clampToWorkArea(
                screen.x + screen.width - w + horizontalOffset,
                screen.y + screen.height - h + verticalOffset,
                w,
                h,
                screen,
            )
    }
}

/**
 * Keeps a [width] x [height] window inside the work area [sb]. A window larger than the work area
 * keeps its top-left corner on screen, where content and close affordances usually start.
 */
private fun clampToWorkArea(
    x: Int,
    y: Int,
    width: Int,
    height: Int,
    sb: ScreenRect,
): WindowPosition {
    val cx = if (width >= sb.width) sb.x else x.coerceIn(sb.x, sb.x + sb.width - width)
    val cy = if (height >= sb.height) sb.y else y.coerceIn(sb.y, sb.y + sb.height - height)
    return WindowPosition(x = cx.dp, y = cy.dp)
}

private fun dpiAwareHalfIconOffset(): Int {
    val scale = TrayScreenGeometry.scale()
    return (15 * scale).roundToInt().coerceAtLeast(0)
}

private fun syntheticClickFromCorner(
    corner: TrayPosition,
    screen: ScreenRect,
): Pair<Int, Int> {
    val half = dpiAwareHalfIconOffset()
    val x =
        if (corner == TrayPosition.TOP_RIGHT || corner == TrayPosition.BOTTOM_RIGHT) {
            screen.x + screen.width - half
        } else {
            screen.x + half
        }
    val y =
        if (corner == TrayPosition.BOTTOM_LEFT || corner == TrayPosition.BOTTOM_RIGHT) {
            screen.y + screen.height - half
        } else {
            screen.y + half
        }
    return x to y
}

// ── Persistence (restore the popup position across launches) ──────────────────────

private const val PROPERTIES_FILE = "tray_position.properties"
private const val POSITION_KEY = "TrayPosition"
private const val X_KEY = "TrayX"
private const val Y_KEY = "TrayY"

private fun trayPropertiesFile(): File {
    val appId = AppIdProvider.appId()
    val tmpBase = System.getProperty("java.io.tmpdir") ?: "."
    val tmpDir = File(File(tmpBase, "ComposeNativeTray"), appId)
    val macCacheDir = macCacheDir()?.resolve(appId)
    val candidates = listOfNotNull(tmpDir, macCacheDir)
    for (dir in candidates) {
        runCatching { if (!dir.exists()) dir.mkdirs() }
        if (dir.exists() && dir.canWrite()) return File(dir, PROPERTIES_FILE)
    }
    return File(tmpDir, PROPERTIES_FILE)
}

private fun macCacheDir(): File? {
    val userHome = System.getProperty("user.home") ?: return null
    return File(userHome).resolve("Library").resolve("Caches").resolve("ComposeNativeTray")
}

private fun loadPersistedClick(): ResolvedClick? {
    val file = trayPropertiesFile()
    if (!file.exists()) return null
    val props = runCatching { Properties().apply { file.inputStream().use(::load) } }.getOrNull() ?: return null
    val x = props.getProperty(X_KEY)?.toIntOrNull() ?: return null
    val y = props.getProperty(Y_KEY)?.toIntOrNull() ?: return null
    val corner =
        props.getProperty(POSITION_KEY)?.let { runCatching { TrayPosition.valueOf(it) }.getOrNull() } ?: return null
    return ResolvedClick(x, y, corner)
}

private fun savePersistedClick(resolved: ResolvedClick) {
    val file = trayPropertiesFile()
    val props =
        runCatching {
            Properties().apply { if (file.exists()) file.inputStream().use(::load) }
        }.getOrElse { Properties() }
    props.setProperty(POSITION_KEY, resolved.corner.name)
    props.setProperty(X_KEY, resolved.x.toString())
    props.setProperty(Y_KEY, resolved.y.toString())
    file.parentFile?.let { runCatching { if (!it.exists()) it.mkdirs() } }
    runCatching { file.outputStream().use { props.store(it, null) } }
}
