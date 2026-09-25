package dev.nucleusframework.window.tao.scene

import org.jetbrains.skia.BackendRenderTarget
import org.jetbrains.skia.ColorSpace
import org.jetbrains.skia.DirectContext
import org.jetbrains.skia.FramebufferFormat
import org.jetbrains.skia.Surface
import org.jetbrains.skia.SurfaceColorFormat
import org.jetbrains.skia.SurfaceOrigin

/**
 * Per-frame Skia/GL rendering helper shared by the NativeView blending
 * overlay and popup scene layers. Wraps the default GL framebuffer in a
 * Skia [Surface], lets the scene paint, then presents.
 *
 * Caller must:
 *  1. have bound the surface's GL context (via the overlay/popup bridge),
 *  2. have called [DirectContext.resetGLAll] on [directContext] so Skia's
 *     GL state cache reflects reality after the external surface switch,
 *  3. provide [present] (the bridge's `nativeSwapBuffers`).
 */
internal inline fun renderGlFrame(
    widthPx: Int,
    heightPx: Int,
    directContext: DirectContext,
    bundle: TaoSceneBundle,
    clearColorArgb: Int,
    // No default on purpose: `false` attaches LCD SurfaceProps on Windows, and
    // silently inheriting it on a per-pixel-alpha surface ships color-fringed
    // text. Every call site must state its surface's alpha mode.
    windowTransparent: Boolean,
    crossinline present: () -> Unit,
) {
    renderGlFrame(
        widthPx = widthPx,
        heightPx = heightPx,
        directContext = directContext,
        clearColorArgb = clearColorArgb,
        windowTransparent = windowTransparent,
        present = present,
    ) { canvas, nanoTime ->
        bundle.render(canvas, nanoTime)
    }
}

internal fun makeTaoGlSurface(
    context: DirectContext,
    rt: BackendRenderTarget,
    windowTransparent: Boolean,
): Surface? =
    Surface.makeFromBackendRenderTarget(
        context = context,
        rt = rt,
        origin = SurfaceOrigin.BOTTOM_LEFT,
        colorFormat = SurfaceColorFormat.RGBA_8888,
        colorSpace = ColorSpace.sRGB,
        surfaceProps = lcdSurfaceProps(windowTransparent),
    )

internal inline fun renderGlFrame(
    widthPx: Int,
    heightPx: Int,
    directContext: DirectContext,
    clearColorArgb: Int,
    // No default on purpose — see the overload above.
    windowTransparent: Boolean,
    crossinline present: () -> Unit,
    crossinline render: (org.jetbrains.skia.Canvas, Long) -> Unit,
) {
    if (widthPx <= 0 || heightPx <= 0) return
    val rt =
        BackendRenderTarget.makeGL(
            width = widthPx,
            height = heightPx,
            sampleCnt = 0,
            stencilBits = 8,
            fbId = 0,
            fbFormat = FramebufferFormat.GR_GL_RGBA8,
        )
    val surface =
        makeTaoGlSurface(directContext, rt, windowTransparent) ?: run {
            rt.close()
            return
        }
    try {
        surface.canvas.clear(clearColorArgb)
        render(surface.canvas, System.nanoTime())
        surface.flushAndSubmit(syncCpu = false)
        present()
    } finally {
        surface.close()
        rt.close()
    }
}
