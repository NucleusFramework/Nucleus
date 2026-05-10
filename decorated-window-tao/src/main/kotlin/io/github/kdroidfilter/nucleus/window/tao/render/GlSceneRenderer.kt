package io.github.kdroidfilter.nucleus.window.tao.render

import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.graphics.asComposeCanvas
import androidx.compose.ui.scene.ComposeScene
import org.jetbrains.skia.BackendRenderTarget
import org.jetbrains.skia.ColorSpace
import org.jetbrains.skia.DirectContext
import org.jetbrains.skia.FramebufferFormat
import org.jetbrains.skia.Surface
import org.jetbrains.skia.SurfaceColorFormat
import org.jetbrains.skia.SurfaceOrigin

/**
 * Per-frame Skia/WGL rendering helper shared by the overlay controller
 * and the popup scene layer. Wraps the default GL framebuffer in a
 * Skia [Surface], lets the scene paint, then presents.
 *
 * Caller must:
 *  1. have made the WGL context current (via the overlay/popup bridge),
 *  2. have called [DirectContext.resetGLAll] on [directContext] so Skia's
 *     GL state cache reflects reality after the external context switch,
 *  3. provide [present] which does `SwapBuffers + DwmFlush()` (the
 *     bridge's `nativeSwapBuffers` does both).
 */
@OptIn(InternalComposeUiApi::class)
internal inline fun renderGlFrame(
    widthPx: Int,
    heightPx: Int,
    directContext: DirectContext,
    scene: ComposeScene,
    clearColorArgb: Int,
    crossinline present: () -> Unit,
) {
    if (widthPx <= 0 || heightPx <= 0) return
    val rt = BackendRenderTarget.makeGL(
        width = widthPx,
        height = heightPx,
        sampleCnt = 0,
        stencilBits = 8,
        fbId = 0,
        fbFormat = FramebufferFormat.GR_GL_RGBA8,
    )
    val surface = Surface.makeFromBackendRenderTarget(
        context = directContext,
        rt = rt,
        origin = SurfaceOrigin.BOTTOM_LEFT,
        colorFormat = SurfaceColorFormat.RGBA_8888,
        colorSpace = ColorSpace.sRGB,
    ) ?: run {
        rt.close()
        return
    }
    try {
        surface.canvas.clear(clearColorArgb)
        scene.render(surface.canvas.asComposeCanvas(), System.nanoTime())
        surface.flushAndSubmit(syncCpu = false)
        present()
    } finally {
        surface.close()
        rt.close()
    }
}
