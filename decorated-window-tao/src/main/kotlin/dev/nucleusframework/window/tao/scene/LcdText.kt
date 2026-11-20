package dev.nucleusframework.window.tao.scene

import dev.nucleusframework.core.runtime.Platform
import dev.nucleusframework.window.tao.ffi.NativeTaoWindowsDecoBridge
import org.jetbrains.skia.PixelGeometry
import org.jetbrains.skia.SurfaceProps

/**
 * LCD / ClearType text for Tao on Windows (Compose issue #875) — the surface
 * half of the feature.
 *
 * Skia only paints chromatic glyph edges when BOTH the GPU surface has a
 * known pixel geometry AND the paragraph requests
 * `FontSmoothing.SubpixelAntiAlias`. The paragraph half is handled at build
 * time by the Nucleus Gradle plugin (`LcdTextDefaultTransform` patches
 * Compose's `FontRasterizationSettings.PlatformDefault` on Windows), so the
 * backend only attaches the pixel geometry here — and only on opaque Windows
 * windows. Transparent windows, popups (per-pixel-alpha DComp surfaces), and
 * every other OS keep an unknown geometry, which makes Skia fall back to
 * grayscale regardless of what paragraphs request.
 */
internal fun lcdSurfaceProps(
    windowTransparent: Boolean,
    platform: Platform = Platform.Current,
    windowsLcdGeometry: () -> PixelGeometry? = ::windowsLcdPixelGeometry,
): SurfaceProps? {
    if (windowTransparent) return null
    if (platform != Platform.Windows) return null
    val geometry = windowsLcdGeometry() ?: return null
    return SurfaceProps(isDeviceIndependentFonts = false, pixelGeometry = geometry)
}

internal fun windowsLcdPixelGeometry(): PixelGeometry? = cachedWindowsLcdGeometry

// The smoothing answer is effectively static for the app's lifetime, and this
// sits inside per-frame surface creation — one JNI query, not 1-3 syscalls per
// rendered frame of every host/overlay/popup.
private val cachedWindowsLcdGeometry: PixelGeometry? by lazy(::queryWindowsLcdPixelGeometry)

private fun queryWindowsLcdPixelGeometry(): PixelGeometry? {
    // Unknown smoothing state (lib missing or query failure) means grayscale,
    // never an assumed RGB stripe order.
    if (!NativeTaoWindowsDecoBridge.isLoaded) return null
    val code =
        try {
            NativeTaoWindowsDecoBridge.nativeFontSmoothingPixelGeometry()
        } catch (_: UnsatisfiedLinkError) {
            return null
        }
    return when (code) {
        NativeTaoWindowsDecoBridge.FONT_SMOOTHING_RGB -> PixelGeometry.RGB_H
        NativeTaoWindowsDecoBridge.FONT_SMOOTHING_BGR -> PixelGeometry.BGR_H
        else -> null
    }
}
