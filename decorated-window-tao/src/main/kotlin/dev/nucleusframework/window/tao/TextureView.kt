package dev.nucleusframework.window.tao

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
 * [nucleusIOSurfaceTextureSource] / [nucleusMetalTextureSource] (macOS),
 * [nucleusDmaBufTextureSource] / [nucleusYuvDmaBufTextureSource] /
 * [nucleusEglImageTextureSource] (Linux).
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
 * Single-plane 32-bit RGB DRM FourCC codes accepted by
 * [nucleusDmaBufTextureSource]. The names follow the DRM convention, where the
 * channel order is the one seen by a little-endian 32-bit read — so
 * [ARGB8888] is `B, G, R, A` in memory, the layout GBM and Wayland
 * compositors use by default.
 *
 * The FourCC is handed to the driver, which sets the imported texture up so GL
 * sampling always yields (R, G, B, A) — no channel order leaks into
 * application code. The `X` variants have no alpha channel and sample as
 * opaque.
 */
public object NucleusDrmFormat {
    /** `AR24` — `DRM_FORMAT_ARGB8888`, GBM's and Wayland's default. */
    public const val ARGB8888: Int = 0x34325241

    /** `XR24` — `DRM_FORMAT_XRGB8888` (no alpha). */
    public const val XRGB8888: Int = 0x34325258

    /** `AB24` — `DRM_FORMAT_ABGR8888`, i.e. `R, G, B, A` in memory. */
    public const val ABGR8888: Int = 0x34324241

    /** `XB24` — `DRM_FORMAT_XBGR8888` (no alpha). */
    public const val XBGR8888: Int = 0x34324258

    /** `DRM_FORMAT_MOD_INVALID` — "the buffer layout is implicit". */
    public const val MODIFIER_INVALID: Long = 0x00FFFFFFFFFFFFFFL

    /** `DRM_FORMAT_MOD_LINEAR` — untiled, row-major. */
    public const val MODIFIER_LINEAR: Long = 0L
}

/**
 * Linux source: one plane of a **DMA-BUF** ([fd]) — the platform's shareable
 * GPU buffer and the counterpart of the DXGI shared handle on Windows and the
 * `IOSurface` on macOS. It is imported as an `EGLImage` on the window's own
 * `EGLDisplay` and bound onto a GL texture Skia samples, so the producer's
 * pixels are never copied: they are the pixels the compositor reads, whatever
 * device or process produced them.
 *
 * The buffer must be single-plane 32-bit RGB ([fourcc], see [NucleusDrmFormat])
 * with premultiplied alpha, [widthPx] × [heightPx], [stride] bytes per row and
 * the plane starting at [offset]. [modifier] is the DRM format modifier the
 * allocator picked (`gbm_bo_get_modifier`, a Wayland
 * `zwp_linux_dmabuf_v1` feedback event, …) — pass
 * [NucleusDrmFormat.MODIFIER_INVALID] to let the driver assume an implicit
 * layout. Explicit modifiers need `EGL_EXT_image_dma_buf_import_modifiers`
 * (universal on Mesa and NVIDIA); the import fails cleanly otherwise and
 * [TextureView] renders an empty `Box`.
 *
 * [fd] stays owned by the caller: EGL takes its own reference to the buffer at
 * import time, so it may be closed as soon as every [TextureView] using this
 * source has been composed once — keeping it open for the producer's lifetime
 * is the simple, safe choice.
 *
 * Synchronization: sampling is zero-copy, so there is no per-frame copy to
 * order against — but nothing implicitly fences the producer's writes either.
 * Producers should finish them (`glFinish`, `vkQueueWaitIdle`, or double
 * buffering) *before* calling [TextureViewController.markFrameAvailable]; a
 * producer still writing while the compositor samples can tear, never crash.
 */
@Suppress("LongParameterList")
public fun nucleusDmaBufTextureSource(
    fd: Int,
    widthPx: Int,
    heightPx: Int,
    stride: Int,
    fourcc: Int = NucleusDrmFormat.ARGB8888,
    offset: Int = 0,
    modifier: Long = NucleusDrmFormat.MODIFIER_INVALID,
): TextureViewSource = DmaBufTextureSource(fd, widthPx, heightPx, stride, fourcc, offset, modifier)

internal data class DmaBufTextureSource(
    val fd: Int,
    val widthPx: Int,
    val heightPx: Int,
    val stride: Int,
    val fourcc: Int,
    val offset: Int,
    val modifier: Long,
) : TextureViewSource

/**
 * Linux source: a producer-owned `EGLImageKHR` ([eglImage] as its raw pointer),
 * for same-process producers that already have one — a GStreamer / VA-API
 * pipeline, or a renderer that imported its own DMA-BUF. Only the GL texture
 * bound onto it belongs to [TextureView]; the image itself stays the producer's
 * to destroy, and must outlive every [TextureView] using this source.
 *
 * The image **must** have been created on the same `EGLDisplay` as the window
 * (i.e. the display of the session's GPU connection) and describe premultiplied
 * 32-bit RGB pixels. Same frame-signalling and synchronization contract as
 * [nucleusDmaBufTextureSource].
 */
public fun nucleusEglImageTextureSource(
    eglImage: Long,
    widthPx: Int,
    heightPx: Int,
): TextureViewSource = EglImageTextureSource(eglImage, widthPx, heightPx)

internal data class EglImageTextureSource(
    val eglImage: Long,
    val widthPx: Int,
    val heightPx: Int,
) : TextureViewSource

/**
 * One plane of a DMA-BUF, for [nucleusYuvDmaBufTextureSource]: [fd] with
 * [stride] bytes per row, the plane's first byte at [offset], laid out according
 * to [modifier] (see [nucleusDmaBufTextureSource] for what a modifier is).
 *
 * Planes may share a single [fd] — what a decoder allocating one buffer hands
 * out, the offsets separating the planes — or carry one descriptor each; both are
 * imported the same way. [fd] stays owned by the caller.
 */
public data class NucleusDmaBufPlane(
    val fd: Int,
    val stride: Int,
    val offset: Int = 0,
    val modifier: Long = NucleusDrmFormat.MODIFIER_INVALID,
)

/**
 * 8-bit 4:2:0 planar layouts [nucleusYuvDmaBufTextureSource] accepts: the
 * **three-plane** ones, whose luma and two chroma planes are each a plain
 * single-channel image the GPU can sample directly. [planeCount] is how many
 * [NucleusDmaBufPlane]s the source needs — three, for now.
 *
 * The two-plane layouts (`NV12` and `NV21`, whose Cb and Cr are interleaved in one
 * plane) are **not supported yet**, which is a limitation of the Skia build
 * Compose ships rather than of the import: it maps no colour type to a GPU
 * two-channel texture, so the chroma plane cannot be sampled as one. If your
 * producer hands out `NV12`, ask it for a three-plane format — VA-API, V4L2 and
 * GBM all allocate `I420`/`YV12` — or de-interleave the chroma yourself.
 */
public enum class NucleusYuvFormat(
    internal val planeCount: Int,
) {
    /** `DRM_FORMAT_YUV420` (`I420`) — Y, then a Cb and a Cr plane at half resolution. */
    I420(PLANAR_420_PLANES),

    /** `DRM_FORMAT_YVU420` (`YV12`) — as [I420], with the two chroma planes swapped. */
    YV12(PLANAR_420_PLANES),
}

/** Luma, Cb and Cr — one plane each, which is what a three-plane layout means. */
private const val PLANAR_420_PLANES = 3

/**
 * Matrix and quantisation range the consumer converts Y'CbCr with. This is
 * metadata the producer owns — a decoder gets it from the bitstream (H.264/HEVC
 * VUI, a `V4L2_COLORSPACE_*`, a `VAProcColorStandardType`) — and getting it wrong
 * shows up as washed-out or oversaturated colour, never as a failure.
 *
 * `LIMITED` (studio swing, Y' in 16..235) is what video pipelines produce;
 * `FULL` (0..255) is what still-image and screen-capture pipelines produce.
 * [BT709_LIMITED] is the HD default and the default here.
 */
public enum class NucleusYuvColorSpace {
    /** SDTV / JPEG primaries, studio swing. */
    BT601_LIMITED,

    /** SDTV / JPEG primaries, full swing — "JFIF" YCbCr. */
    BT601_FULL,

    /** HD primaries, studio swing. */
    BT709_LIMITED,

    /** HD primaries, full swing. */
    BT709_FULL,
}

/**
 * Linux source: a **planar YUV** DMA-BUF — a hardware video decoder's native
 * output (VA-API, V4L2 stateful/stateless, NVDEC through EGL) — composited with
 * no CPU copy and no colour conversion pass of its own.
 *
 * Each plane of [planes] is imported as an independent single-channel `EGLImage`
 * on the window's `EGLDisplay`, exactly like [nucleusDmaBufTextureSource] does for
 * a packed RGB buffer, and the three are combined by one shader while the scene is
 * drawn: the conversion happens inside the draw that samples them, so there is
 * still no copy, no intermediate RGB surface and no per-frame work beyond the draw
 * itself. Importing the buffer *whole* and letting the driver convert would need
 * `GL_TEXTURE_EXTERNAL_OES`, which desktop GL does not have.
 *
 * [widthPx] × [heightPx] is the **luma** size and must be even; chroma planes are
 * half that in both directions. Plane order follows [format], and their count
 * must match [NucleusYuvFormat.planeCount] — the one thing checked eagerly, since
 * it can only be a programming error. Anything the driver refuses (a modifier it
 * cannot read, a plane stride below the row size) fails the import instead, and
 * [TextureView] renders an empty `Box`.
 *
 * Same fd ownership and synchronization contract as
 * [nucleusDmaBufTextureSource] — including the option of handing over an acquire
 * fence rather than finishing the writes, see
 * [TextureViewController.markFrameAvailable].
 */
public fun nucleusYuvDmaBufTextureSource(
    widthPx: Int,
    heightPx: Int,
    format: NucleusYuvFormat,
    planes: List<NucleusDmaBufPlane>,
    colorSpace: NucleusYuvColorSpace = NucleusYuvColorSpace.BT709_LIMITED,
): TextureViewSource {
    require(planes.size == format.planeCount) {
        "$format needs ${format.planeCount} DMA-BUF planes, got ${planes.size}"
    }
    // Copied: the source is a registry key, so it must not change under the
    // import once a caller keeps mutating the list it passed.
    return YuvDmaBufTextureSource(widthPx, heightPx, format, planes.toList(), colorSpace)
}

internal data class YuvDmaBufTextureSource(
    val widthPx: Int,
    val heightPx: Int,
    val format: NucleusYuvFormat,
    val planes: List<NucleusDmaBufPlane>,
    val colorSpace: NucleusYuvColorSpace,
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

    /** Acquire fence of the newest frame, or [NO_FENCE]. Guarded by `this`. */
    private var acquireFence = NO_FENCE

    /**
     * Whether a fence is worth looking for at all. Read once per draw pass by
     * every attached view, so it is a volatile flag rather than the lock: a
     * producer on the default contract must keep costing the draw nothing.
     */
    @Volatile
    internal var hasAcquireFence: Boolean = false
        private set

    /** Signals that the producer published a new frame. Any thread. */
    public fun markFrameAvailable(): Unit = signalFrame(NO_FENCE)

    /**
     * Signals a new frame together with the **acquire fence** the compositor must
     * wait for before sampling it: a `sync_file` descriptor, as
     * `eglDupNativeFenceFDANDROID`, `VK_KHR_external_fence_fd` or a V4L2 / VA-API
     * out-fence produce. The consumer's GPU waits on it, so the producer does not
     * have to finish its writes on the CPU before signalling — the alternative to
     * the `glFinish` the plain [markFrameAvailable] contract asks for.
     *
     * **Linux DMA-BUF sources only**, and only on a driver with
     * `EGL_ANDROID_native_fence_sync` (Mesa and NVIDIA both ship it). There, this
     * takes ownership of [acquireFenceFd]: it is closed when the next frame is
     * signalled or when [rememberTextureViewController]'s composition leaves, and
     * every surface compositing the frame waits on a dup of it. Everywhere else
     * the descriptor is ignored and stays the caller's — passing a fence to a
     * Windows or macOS source is a no-op, not a leak.
     *
     * Pass [NO_FENCE] to signal a frame with no fence. Any thread.
     */
    public fun markFrameAvailable(acquireFenceFd: Int): Unit = signalFrame(acquireFenceFd)

    /**
     * Runs [block] with the newest frame's acquire fence, or returns null when
     * there is none. Held under the lock, so the descriptor cannot be closed by a
     * concurrent [markFrameAvailable] while the caller waits on it.
     */
    internal fun <T : Any> withAcquireFence(block: (Int) -> T): T? =
        synchronized(this) {
            if (acquireFence == NO_FENCE) null else block(acquireFence)
        }

    /** Closes the fence still held, if any — the composition is going away. */
    internal fun releaseAcquireFence() {
        synchronized(this) {
            if (acquireFence != NO_FENCE) closeAcquireFenceFd(acquireFence)
            acquireFence = NO_FENCE
            hasAcquireFence = false
        }
    }

    private fun signalFrame(fenceFd: Int) {
        // Synchronized so concurrent producers still yield distinct,
        // monotonic stamps (a lost increment could suppress a redraw).
        synchronized(this) {
            // The previous frame's fence is obsolete: this frame supersedes it,
            // and whoever was going to wait on it now has a newer one (or none,
            // which means the producer finished its writes itself).
            if (acquireFence != NO_FENCE) closeAcquireFenceFd(acquireFence)
            acquireFence = if (fenceFd != NO_FENCE && canOwnAcquireFence()) fenceFd else NO_FENCE
            hasAcquireFence = acquireFence != NO_FENCE
            frameStamp.longValue += 1
        }
    }

    public companion object {
        /** "No acquire fence" — the value [markFrameAvailable] treats as absent. */
        public const val NO_FENCE: Int = -1
    }
}

/**
 * Remembers a [TextureViewController] for the current composition, releasing the
 * acquire fence it may still hold when that composition goes away.
 */
@Composable
public fun rememberTextureViewController(): TextureViewController {
    val controller = remember { TextureViewController() }
    DisposableEffect(controller) {
        onDispose { controller.releaseAcquireFence() }
    }
    return controller
}

/**
 * Composites an externally produced GPU texture inside the Compose
 * scene — the "passive GPU pixels" counterpart of [NativeView]
 * (discussion #338), equivalent in spirit to Flutter's `Texture`
 * widget. Unlike [NativeView], the pixels take part in normal
 * composition: z-order, clipping, `Modifier.graphicsLayer` transforms
 * and scrolling all apply, and no CPU frame copy ever happens (the
 * producer's texture is imported straight onto the GPU device the window
 * renders with — ANGLE's shared-resource import on Windows, an
 * `IOSurface`-backed `MTLTexture` on macOS, a DMA-BUF `EGLImage` on Linux).
 *
 * Frame updates flow through [controller]: the producer renders, then
 * calls [TextureViewController.markFrameAvailable] (any thread) — only
 * the draw pass re-executes, never recomposition. Several [TextureView]s
 * sharing one [source] also share a single GPU import.
 *
 * Input is deliberately not handled — the composable is a plain drawing
 * surface; interactive native widgets remain [NativeView] territory.
 *
 * **Windows, macOS and Linux (Tao backend).** When [source] is null, does not
 * match the running platform, or the import fails (ANGLE/Metal/EGL DMA-BUF
 * import unavailable, bad handle), it renders as an empty `Box(modifier)`.
 *
 * @param source producer texture handle, see [nucleusD3D11SharedTextureSource]
 *   (Windows), [nucleusIOSurfaceTextureSource] (macOS) and
 *   [nucleusDmaBufTextureSource] / [nucleusYuvDmaBufTextureSource] (Linux).
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
        is DmaBufTextureSource, is EglImageTextureSource, is YuvDmaBufTextureSource ->
            LinuxTextureView(source, modifier, controller, filterQuality, contentScale, alignment)
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
    val dstRect = externalTextureDstRect(srcRect, contentScale, alignment)
    clipRect {
        drawIntoCanvas { canvas ->
            canvas.skiaCanvas.drawImageRect(image, srcRect, dstRect, sampling, null, true)
        }
    }
}

/**
 * Where a texture of [srcRect]'s size lands inside the composable's bounds under
 * [contentScale] and [alignment] — the geometry half of [drawExternalTexture],
 * split out because the Linux planar path draws a shader over that rectangle
 * instead of an image into it.
 */
internal fun DrawScope.externalTextureDstRect(
    srcRect: Rect,
    contentScale: ContentScale,
    alignment: Alignment,
): Rect {
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
    return Rect.makeXYWH(offset.x.toFloat(), offset.y.toFloat(), scaledW, scaledH)
}
