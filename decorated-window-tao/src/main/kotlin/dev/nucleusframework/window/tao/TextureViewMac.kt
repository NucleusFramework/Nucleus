package dev.nucleusframework.window.tao

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.layout.ContentScale
import dev.nucleusframework.core.runtime.Platform
import dev.nucleusframework.window.tao.ffi.NativeTaoMacOsTextureBridge
import dev.nucleusframework.window.tao.scene.LocalTaoMetalTextureHost
import dev.nucleusframework.window.tao.scene.TaoMetalTextureHost
import org.jetbrains.skia.BackendRenderTarget
import org.jetbrains.skia.ColorSpace
import org.jetbrains.skia.ContentChangeMode
import org.jetbrains.skia.DirectContext
import org.jetbrains.skia.Image
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface
import org.jetbrains.skia.SurfaceColorFormat
import org.jetbrains.skia.SurfaceOrigin

/**
 * macOS implementation of [TextureView]. The producer's `IOSurface` (or
 * `id<MTLTexture>`) is mapped as an `id<MTLTexture>` on the window's own Metal
 * device and wrapped in a Skia [Surface]; each producer frame is then pulled
 * into an immutable GPU [Image] with `makeImageSnapshot()` and composited into
 * the Compose scene.
 *
 * The snapshot is why macOS needs one GPU-GPU copy per frame: skiko exposes
 * `BackendRenderTarget.makeMetal` but no Metal `BackendTexture`, so Skia can
 * only *wrap* the import as a render target, and an image of a wrapped render
 * target is always a copy. The copy is recorded on the window's Skia context
 * and executed inside the same flush as the draw that samples it, so the
 * composited frame is always the one the producer had published when the frame
 * was drawn — the equivalent of the Windows keyed-mutex staging path.
 *
 * Threading follows the macOS record/replay split: the composable's draw pass
 * runs on the main thread and hops to the render thread that owns the Skia
 * `DirectContext` (idle at that point, see [TaoMetalTextureHost]) for the
 * snapshot. The hop happens once per new producer frame per import, no matter
 * how many [TextureView]s share it.
 */
@Composable
internal fun MacTextureView(
    source: TextureViewSource,
    modifier: Modifier,
    controller: TextureViewController?,
    filterQuality: FilterQuality,
    contentScale: ContentScale,
    alignment: Alignment,
) {
    val host = LocalTaoMetalTextureHost.current
    if (Platform.Current != Platform.MacOS || host == null || !NativeTaoMacOsTextureBridge.isLoaded) {
        Box(modifier)
        return
    }

    val imported =
        remember(source, host) {
            TextureImportLease(metalTextureImports, host, source)
        }.imported
    if (imported == null) {
        Box(modifier)
        return
    }

    val srcRect =
        remember(imported) {
            Rect(0f, 0f, imported.widthPx.toFloat(), imported.heightPx.toFloat())
        }
    val sampling = remember(filterQuality) { samplingFor(filterQuality) }
    Box(
        modifier.drawBehind {
            // Snapshot read of the frame stamp: markFrameAvailable()
            // invalidates exactly this draw pass, nothing recomposes.
            val stamp = controller?.frameStamp?.longValue ?: 0L
            val image = imported.snapshot(controller, stamp) ?: return@drawBehind
            drawExternalTexture(image, srcRect, contentScale, alignment, sampling)
        },
    )
}

/**
 * One imported external texture: the native `id<MTLTexture>` mapping plus the
 * Skia objects wrapping it, and the current frame's snapshot. Everything Skia
 * touches lives on [host]'s render thread; the fields are only read/written
 * from the main thread (draw pass, composition, disposal), which the blocking
 * [TaoMetalTextureHost.runOnRenderThread] hops keep ordered.
 */
private class MacImportedTexture(
    val handle: Long,
    val host: TaoMetalTextureHost,
    private val renderTarget: BackendRenderTarget,
    private val surface: Surface,
    val widthPx: Int,
    val heightPx: Int,
) {
    private var image: Image? = null

    /** One snapshot per producer frame per controller — see [FrameStampGate]. */
    private val consumed = FrameStampGate()

    /**
     * Current GPU snapshot of the producer surface, re-pulled once per signalled
     * frame however many views share this import. The previous image is closed
     * only once the new one exists — pictures recorded from earlier frames keep
     * their own Skia reference, so an in-flight replay is unaffected, and a
     * failed snapshot leaves the last good frame on screen instead of blanking
     * the view.
     */
    fun snapshot(
        controller: TextureViewController?,
        stamp: Long,
    ): Image? {
        val current = image
        if (current != null && !consumed.isPending(controller, stamp)) return current
        // Recorded even when the snapshot below fails: a broken GPU state must
        // not turn every subsequent draw pass into a blocking render-thread hop.
        // The next producer frame re-arms the retry.
        consumed.markConsumed(controller, stamp)
        val fresh =
            host.runOnRenderThread {
                runCatching {
                    // The producer writes the wrapped texture behind Skia's
                    // back, so Skia still believes the surface is unchanged and
                    // would hand back its cached snapshot (the first frame,
                    // frozen forever). This is the API for exactly that case:
                    // it drops the cached image and bumps the generation id.
                    // RETAIN — the producer's pixels must survive, we only
                    // invalidate Skia's bookkeeping.
                    surface.notifyContentWillChange(ContentChangeMode.RETAIN)
                    surface.makeImageSnapshot()
                }.getOrNull()?.also { current?.close() }
            }
        if (fresh != null) image = fresh
        return image
    }

    fun close() {
        // Skia teardown must happen on the context's thread, before the native
        // texture goes. The hop only fails once the render thread is gone — i.e.
        // after the host closed its DirectContext, which already freed these
        // objects — so swallowing that is safe, but the native import (and the
        // IOSurface reference it holds) must be released either way.
        runCatching {
            host.runOnRenderThread {
                image?.close()
                surface.close()
                renderTarget.close()
            }
        }
        image = null
        consumed.clear()
        NativeTaoMacOsTextureBridge.nativeDestroy(handle)
    }
}

/**
 * The macOS import ledger. Refcounted and keyed by Skia context + source; see
 * [TextureImportRegistry] for why, and for the scaffolding the three backends
 * share. Nothing calls `closeAllFor` here: a macOS surface closes its
 * `DirectContext` only after the composition that leased through it is gone.
 */
private val metalTextureImports =
    TextureImportRegistry<TaoMetalTextureHost, MacImportedTexture>(
        contextOf = { it.directContext },
        importTexture = ::importTexture,
        closeImport = { it.close() },
    )

/** Whether any `TextureView` import is currently alive on [context] — the headful suite's leak probe. */
internal fun hasMetalTextureImports(context: DirectContext): Boolean = metalTextureImports.hasImportsFor(context)

private fun importTexture(
    host: TaoMetalTextureHost,
    source: TextureViewSource,
): MacImportedTexture? {
    val widthPx: Int
    val heightPx: Int
    when (source) {
        is IOSurfaceTextureSource -> {
            widthPx = source.widthPx
            heightPx = source.heightPx
        }
        is MetalTextureSource -> {
            widthPx = source.widthPx
            heightPx = source.heightPx
        }
        else -> return null
    }
    if (widthPx < 1 || heightPx < 1 || host.metalDevicePtr == 0L) return null

    // The whole import runs on the render thread: the texture must be created
    // on the device Skia renders with, and the Skia wrappers are context-bound.
    return host.runOnRenderThread {
        val handle =
            when (source) {
                is IOSurfaceTextureSource ->
                    NativeTaoMacOsTextureBridge.nativeImportIOSurface(
                        host.metalDevicePtr,
                        source.ioSurface,
                        widthPx,
                        heightPx,
                    )
                is MetalTextureSource ->
                    NativeTaoMacOsTextureBridge.nativeImportMetalTexture(
                        host.metalDevicePtr,
                        source.metalTexture,
                        widthPx,
                        heightPx,
                    )
            }
        if (handle <= 0L) return@runOnRenderThread null
        val texturePtr = NativeTaoMacOsTextureBridge.nativeTexturePtr(handle)
        if (texturePtr == 0L) {
            NativeTaoMacOsTextureBridge.nativeDestroy(handle)
            return@runOnRenderThread null
        }
        val colorFormat =
            if (NativeTaoMacOsTextureBridge.nativePixelFormat(handle) == NativeTaoMacOsTextureBridge.FORMAT_RGBA8) {
                SurfaceColorFormat.RGBA_8888
            } else {
                SurfaceColorFormat.BGRA_8888
            }
        val renderTarget = BackendRenderTarget.makeMetal(widthPx, heightPx, texturePtr)
        val surface =
            runCatching {
                Surface.makeFromBackendRenderTarget(
                    context = host.directContext,
                    rt = renderTarget,
                    origin = SurfaceOrigin.TOP_LEFT,
                    colorFormat = colorFormat,
                    colorSpace = ColorSpace.sRGB,
                )
            }.getOrNull()
        if (surface == null) {
            renderTarget.close()
            NativeTaoMacOsTextureBridge.nativeDestroy(handle)
            return@runOnRenderThread null
        }
        MacImportedTexture(handle, host, renderTarget, surface, widthPx, heightPx)
    }
}
