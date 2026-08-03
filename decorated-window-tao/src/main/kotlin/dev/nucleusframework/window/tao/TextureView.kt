package dev.nucleusframework.window.tao

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.skiaCanvas
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.IntSize
import org.jetbrains.skia.FilterMipmap
import org.jetbrains.skia.FilterMode
import org.jetbrains.skia.Image
import org.jetbrains.skia.MipmapMode
import org.jetbrains.skia.Rect
import org.jetbrains.skia.SamplingMode
import kotlin.math.roundToInt

/**
 * Handle to an external GPU texture composited by [TextureView].
 * Obtain one from a platform-specific factory:
 * [nucleusD3D11SharedTextureSource] (Windows),
 * [nucleusIOSurfaceTextureSource] / [nucleusMetalTextureSource] (macOS).
 */
public sealed interface TextureViewSource

/**
 * Windows source: a D3D11 texture shared through a **legacy** DXGI
 * shared handle (`IDXGIResource::GetSharedHandle`). NT handles
 * (`D3D11_RESOURCE_MISC_SHARED_NTHANDLE`) are not supported by ANGLE's
 * import path.
 *
 * Synchronization is picked automatically from the producer texture:
 *
 *  - `D3D11_RESOURCE_MISC_SHARED_KEYEDMUTEX` — **recommended**. Each
 *    [TextureViewController.markFrameAvailable] pulls the frame into a
 *    private staging texture under `AcquireSync(0)`/`ReleaseSync(0)`;
 *    rendering is tear-free at the cost of one GPU-GPU copy per frame.
 *    The producer must bracket its writes the same way (acquire key 0,
 *    write, release key 0).
 *  - plain `D3D11_RESOURCE_MISC_SHARED` — true zero copy, Skia samples
 *    the producer texture live. The producer must `Flush()` after each
 *    frame; a racing redraw may sample a partially written frame.
 *
 * The texture must be `R8G8B8A8_UNORM` (or a compatible RGBA8 format)
 * with premultiplied alpha; [widthPx]/[heightPx] must match the D3D
 * texture size exactly.
 */
public fun nucleusD3D11SharedTextureSource(
    sharedHandle: Long,
    widthPx: Int,
    heightPx: Int,
): TextureViewSource = D3D11SharedTextureSource(sharedHandle, widthPx, heightPx)

internal data class D3D11SharedTextureSource(
    val sharedHandle: Long,
    val widthPx: Int,
    val heightPx: Int,
) : TextureViewSource

/**
 * macOS source: an `IOSurfaceRef` ([ioSurface] as its raw pointer) — the
 * platform's shareable GPU buffer and the counterpart of the DXGI shared
 * handle on Windows. The consumer maps it as an `id<MTLTexture>` on the
 * window's own Metal device, so the producer's pixels are never copied
 * on the CPU, whatever device (or process) produced them.
 *
 * The surface must be 32-bit `BGRA` or `RGBA` with premultiplied alpha, and
 * [widthPx] × [heightPx] must match its plane dimensions exactly (Metal
 * validates the texture descriptor against them). It must also be backed by
 * memory the window's GPU can share — true on Apple silicon and Intel
 * integrated GPUs; on a discrete-only Intel Mac the import fails and
 * [TextureView] renders an empty `Box`.
 *
 * Synchronization: Skia's Metal backend exposes no way to sample a wrapped
 * `id<MTLTexture>` directly, so each frame is pulled through one GPU-GPU
 * copy on the window's command queue — the equivalent of the Windows
 * keyed-mutex staging path, minus the mutex. Producers should therefore
 * finish their writes (`commit` + `waitUntilCompleted`, or double buffering)
 * *before* calling [TextureViewController.markFrameAvailable]; a producer
 * still writing while the compositor copies can tear, never crash.
 */
public fun nucleusIOSurfaceTextureSource(
    ioSurface: Long,
    widthPx: Int,
    heightPx: Int,
): TextureViewSource = IOSurfaceTextureSource(ioSurface, widthPx, heightPx)

internal data class IOSurfaceTextureSource(
    val ioSurface: Long,
    val widthPx: Int,
    val heightPx: Int,
) : TextureViewSource

/**
 * macOS source: a producer-owned `id<MTLTexture>` ([metalTexture] as its raw
 * pointer), for same-process Metal producers. The texture is sampled in place
 * when it already lives on the window's Metal device with
 * `MTLTextureUsageRenderTarget`; otherwise its `IOSurface` backing is
 * re-wrapped on that device — which covers foreign-device textures and
 * `CVMetalTextureCache` output (video decoders), whose textures carry no
 * render-target usage.
 *
 * A texture that is neither render-target-capable on the window's device nor
 * `IOSurface`-backed cannot be imported; hand over an
 * [nucleusIOSurfaceTextureSource] in that case. Pixel format must be
 * `BGRA8Unorm` or `RGBA8Unorm` (sRGB variants included) with premultiplied
 * alpha. Same frame-copy and synchronization contract as
 * [nucleusIOSurfaceTextureSource].
 */
public fun nucleusMetalTextureSource(
    metalTexture: Long,
    widthPx: Int,
    heightPx: Int,
): TextureViewSource = MetalTextureSource(metalTexture, widthPx, heightPx)

internal data class MetalTextureSource(
    val metalTexture: Long,
    val widthPx: Int,
    val heightPx: Int,
) : TextureViewSource

/**
 * Frame-availability signal for [TextureView] — the counterpart of
 * Flutter's `markTextureFrameAvailable`. The producer calls
 * [markFrameAvailable] after publishing a frame; only the **draw pass**
 * of the attached [TextureView]s is invalidated (no recomposition, no
 * layout), so a 60 fps producer costs composition nothing.
 *
 * [markFrameAvailable] is safe to call from any thread.
 */
public class TextureViewController {
    // Unboxed: this is written once per producer frame (60-120 Hz per producer),
    // so a boxed Long state would allocate on the hottest path of the feature.
    internal val frameStamp = mutableLongStateOf(0L)

    /** Signals that the producer published a new frame. Any thread. */
    public fun markFrameAvailable() {
        // Synchronized so concurrent producers still yield distinct,
        // monotonic stamps (a lost increment could suppress a redraw).
        synchronized(this) {
            frameStamp.longValue += 1
        }
    }
}

/** Remembers a [TextureViewController] for the current composition. */
@Composable
public fun rememberTextureViewController(): TextureViewController = remember { TextureViewController() }

/**
 * Composites an externally produced GPU texture inside the Compose
 * scene — the "passive GPU pixels" counterpart of [NativeView]
 * (discussion #338), equivalent in spirit to Flutter's `Texture`
 * widget. Unlike [NativeView], the pixels take part in normal
 * composition: z-order, clipping, `Modifier.graphicsLayer` transforms
 * and scrolling all apply, and no CPU frame copy ever happens (the
 * producer's texture is imported straight onto the GPU device the window
 * renders with — ANGLE's shared-resource import on Windows, an
 * `IOSurface`-backed `MTLTexture` on macOS).
 *
 * Frame updates flow through [controller]: the producer renders, then
 * calls [TextureViewController.markFrameAvailable] (any thread) — only
 * the draw pass re-executes, never recomposition. Several [TextureView]s
 * sharing one [source] also share a single GPU import.
 *
 * Input is deliberately not handled — the composable is a plain drawing
 * surface; interactive native widgets remain [NativeView] territory.
 *
 * **Windows and macOS (Tao backend).** On Linux — or when [source] is null,
 * does not match the running platform, or the import fails (ANGLE/Metal
 * unavailable, bad handle) — it renders as an empty `Box(modifier)`.
 *
 * @param source producer texture handle, see [nucleusD3D11SharedTextureSource]
 *   (Windows) and [nucleusIOSurfaceTextureSource] (macOS).
 * @param controller frame-availability signal; omit for static content.
 * @param filterQuality sampling filter, like `Image`'s parameter
 *   ([FilterQuality.None] = nearest, [FilterQuality.High] = cubic).
 * @param contentScale how the texture maps to the composable's bounds
 *   (content outside the bounds is clipped).
 * @param alignment placement of the scaled texture inside the bounds.
 */
@Composable
public fun TextureView(
    source: TextureViewSource?,
    modifier: Modifier = Modifier,
    controller: TextureViewController? = null,
    filterQuality: FilterQuality = FilterQuality.Low,
    contentScale: ContentScale = ContentScale.FillBounds,
    alignment: Alignment = Alignment.Center,
) {
    when (source) {
        is D3D11SharedTextureSource ->
            WindowsTextureView(source, modifier, controller, filterQuality, contentScale, alignment)
        is IOSurfaceTextureSource, is MetalTextureSource ->
            MacTextureView(source, modifier, controller, filterQuality, contentScale, alignment)
        null -> Box(modifier)
    }
}

/** Skia sampling for a Compose [FilterQuality]; mirrors `Image`'s mapping. */
internal fun samplingFor(filterQuality: FilterQuality): SamplingMode =
    when (filterQuality) {
        FilterQuality.None -> FilterMipmap(FilterMode.NEAREST, MipmapMode.NONE)
        FilterQuality.Low -> FilterMipmap(FilterMode.LINEAR, MipmapMode.NONE)
        FilterQuality.Medium -> FilterMipmap(FilterMode.LINEAR, MipmapMode.LINEAR)
        else -> SamplingMode.MITCHELL
    }

/**
 * Draws [image] (the imported texture) into the current draw scope with
 * [contentScale]/[alignment] applied and anything outside the composable's
 * bounds clipped away — shared by both platform implementations.
 */
internal fun DrawScope.drawExternalTexture(
    image: Image,
    srcRect: Rect,
    contentScale: ContentScale,
    alignment: Alignment,
    sampling: SamplingMode,
) {
    val srcSize = Size(srcRect.width, srcRect.height)
    val scaleFactor = contentScale.computeScaleFactor(srcSize, size)
    val scaledW = srcSize.width * scaleFactor.scaleX
    val scaledH = srcSize.height * scaleFactor.scaleY
    val offset =
        alignment.align(
            IntSize(scaledW.roundToInt(), scaledH.roundToInt()),
            IntSize(size.width.roundToInt(), size.height.roundToInt()),
            layoutDirection,
        )
    clipRect {
        drawIntoCanvas { canvas ->
            canvas.skiaCanvas.drawImageRect(
                image,
                srcRect,
                Rect.makeXYWH(offset.x.toFloat(), offset.y.toFloat(), scaledW, scaledH),
                sampling,
                null,
                true,
            )
        }
    }
}
