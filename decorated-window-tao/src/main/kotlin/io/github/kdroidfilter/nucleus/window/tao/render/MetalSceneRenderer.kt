package io.github.kdroidfilter.nucleus.window.tao.render

import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.graphics.asComposeCanvas
import androidx.compose.ui.scene.ComposeScene
import io.github.kdroidfilter.nucleus.window.tao.NativeMetalBridge
import org.jetbrains.skia.BackendRenderTarget
import org.jetbrains.skia.ColorSpace
import org.jetbrains.skia.DirectContext
import org.jetbrains.skia.Surface
import org.jetbrains.skia.SurfaceColorFormat
import org.jetbrains.skia.SurfaceOrigin

/**
 * Single source of truth for the per-frame Skia/Metal rendering dance
 * shared by the main scene host, popup scene layers, and `NativeView`'s
 * overlay controller. Acquires a Metal drawable, wraps it in a Skia
 * [Surface], lets the scene paint, then presents.
 *
 * Skia's Metal API requires re-creating the [Surface] every frame
 * because each drawable wraps a different texture, so the per-frame
 * allocations here are unavoidable.
 *
 * If [nativeBeginFrame] returns null the function is a no-op. If
 * [Surface.makeFromBackendRenderTarget] returns null the drawable is
 * still presented (untextured) so the Metal command queue stays
 * balanced.
 *
 * [present] lets callers swap the default async present
 * (`NativeMetalBridge.nativePresent`) for the synchronous
 * `nativePresentWithInterop` path when AppKit subview mutations need to
 * commit atomically with the Compose frame. The lambda is invoked
 * exactly once per successful `nativeBeginFrame` — including from the
 * failure-path `finally` — so callers relying on it to drain side state
 * (e.g. an interop transaction) don't need to handle missed calls.
 */
@OptIn(InternalComposeUiApi::class)
internal inline fun renderMetalFrame(
    attachmentHandle: Long,
    directContext: DirectContext,
    scene: ComposeScene,
    clearColor: Int,
    crossinline present: (handle: Long, drawablePtr: Long) -> Unit = { h, d ->
        NativeMetalBridge.nativePresent(h, d)
    },
    onAfterPresent: () -> Unit = {},
) {
    val frame = NativeMetalBridge.nativeBeginFrame(attachmentHandle) ?: return
    var presented = false
    try {
        val rt = BackendRenderTarget.makeMetal(frame.widthPx, frame.heightPx, frame.texturePtr)
        val surface = Surface.makeFromBackendRenderTarget(
            context = directContext,
            rt = rt,
            origin = SurfaceOrigin.TOP_LEFT,
            colorFormat = SurfaceColorFormat.BGRA_8888,
            colorSpace = ColorSpace.sRGB,
        ) ?: run {
            rt.close()
            return
        }
        try {
            surface.canvas.clear(clearColor)
            scene.render(surface.canvas.asComposeCanvas(), System.nanoTime())
            surface.flushAndSubmit(syncCpu = false)
            present(attachmentHandle, frame.drawablePtr)
            presented = true
            onAfterPresent()
        } finally {
            surface.close()
            rt.close()
        }
    } finally {
        // Drawable was retained in beginFrame — release via present to
        // balance even when wrapping or rendering failed.
        if (!presented) present(attachmentHandle, frame.drawablePtr)
    }
}
