package dev.nucleusframework.window.tao.ffi

/**
 * JNI bridge for external GPU texture import (TextureView) on macOS.
 * The native code lives in `texture.m`, compiled into
 * `libnucleus_tao_metal.dylib` — loading is delegated to [NativeMetalBridge]
 * (JNI method resolution searches every library loaded by the class loader,
 * so no second `load` is needed). Mirrors the Windows
 * [NativeTaoTextureBridge] / `nucleus_tao_gl.dll` arrangement.
 *
 * Import/destroy must run on the thread owning the Skia Metal `DirectContext`
 * (the host's render thread) so the texture is created on the same
 * `id<MTLDevice>` Skia renders with. Unlike Windows there is no per-frame
 * native call: Skia's own `makeImageSnapshot` performs the frame copy (see
 * `TextureViewMac.kt`). The test producer owns a private device + queue and is
 * safe from any single producer thread.
 */
@Suppress("TooManyFunctions")
internal object NativeTaoMacOsTextureBridge {
    val isLoaded: Boolean get() = NativeMetalBridge.isLoaded

    /** `MTLPixelFormatBGRA8Unorm` import — Skia `SurfaceColorFormat.BGRA_8888`. */
    const val FORMAT_BGRA8: Int = 0

    /** `MTLPixelFormatRGBA8Unorm` import — Skia `SurfaceColorFormat.RGBA_8888`. */
    const val FORMAT_RGBA8: Int = 1

    /**
     * Imports an `IOSurfaceRef` as an `id<MTLTexture>` on [devicePtr]
     * (the host's Metal device): the returned texture aliases the producer's
     * pixels — no CPU copy — and is created with
     * `MTLTextureUsageRenderTarget | MTLTextureUsageShaderRead` so Skia can
     * wrap it via `BackendRenderTarget.makeMetal`.
     *
     * The surface must be 32-bit `BGRA` or `RGBA` with premultiplied alpha and
     * exactly [widthPx] × [heightPx] (Metal validates the descriptor against
     * the plane's dimensions). Returns an opaque handle, or `<= 0` on failure:
     * `-1` bad arguments, `-2` unsupported pixel format, `-3` size mismatch,
     * `-4` texture creation failed — including a surface whose memory the
     * device cannot map as `MTLStorageModeShared` — `0` out of memory.
     */
    @JvmStatic
    external fun nativeImportIOSurface(
        devicePtr: Long,
        ioSurfacePtr: Long,
        widthPx: Int,
        heightPx: Int,
    ): Long

    /**
     * Imports a producer-owned `id<MTLTexture>`. Sampled in place when it
     * already lives on [devicePtr] with `MTLTextureUsageRenderTarget`;
     * otherwise its `IOSurface` backing is re-wrapped on [devicePtr] (the
     * case for foreign-device textures and for `CVMetalTextureCache` output,
     * which carries no render-target usage).
     *
     * Same return contract as [nativeImportIOSurface], plus `-5` (host-device
     * texture without render-target usage and no IOSurface backing) and `-6`
     * (foreign-device texture with no IOSurface backing).
     */
    @JvmStatic
    external fun nativeImportMetalTexture(
        devicePtr: Long,
        texturePtr: Long,
        widthPx: Int,
        heightPx: Int,
    ): Long

    /** `id<MTLTexture>` backing the import — fed to `BackendRenderTarget.makeMetal`. */
    @JvmStatic
    external fun nativeTexturePtr(handle: Long): Long

    /** [FORMAT_BGRA8] or [FORMAT_RGBA8] — picks the Skia `SurfaceColorFormat`. */
    @JvmStatic
    external fun nativePixelFormat(handle: Long): Int

    /**
     * Releases the imported texture (and the retained `IOSurface`). Call after
     * the Skia `Surface`/`BackendRenderTarget` wrapping it have been closed.
     */
    @JvmStatic
    external fun nativeDestroy(handle: Long)

    // ---- Metal test producer (demos / smoke tests) --------------------

    /**
     * Creates a private `MTLDevice` + command queue and a `BGRA` `IOSurface`
     * of the given size, wrapped as a render target. Returns an opaque
     * producer handle, or 0 when Metal is unavailable.
     */
    @JvmStatic
    external fun nativeTestProducerCreate(
        widthPx: Int,
        heightPx: Int,
    ): Long

    /** `IOSurfaceRef` of the producer's texture — the handle consumers import. */
    @JvmStatic
    external fun nativeTestProducerIoSurface(producer: Long): Long

    /** `id<MTLDevice>` of the producer — headless smoke tests build a Skia context on it. */
    @JvmStatic
    external fun nativeTestProducerDevicePtr(producer: Long): Long

    /** `id<MTLCommandQueue>` of the producer. */
    @JvmStatic
    external fun nativeTestProducerQueuePtr(producer: Long): Long

    /**
     * Clears the producer texture to [argb] (premultiplied on the native
     * side), then commits and waits for completion so the frame is fully
     * written before the caller signals it.
     */
    @JvmStatic
    external fun nativeTestProducerFill(
        producer: Long,
        argb: Int,
    )

    /**
     * Draws an animated test pattern ([argbBg] background + two moving white
     * bars driven by [tick]) — gives contentScale/filterQuality demos some
     * structure and makes tearing observable. Same commit-and-wait contract
     * as [nativeTestProducerFill].
     */
    @JvmStatic
    external fun nativeTestProducerDrawPattern(
        producer: Long,
        tick: Int,
        argbBg: Int,
    )

    @JvmStatic
    external fun nativeTestProducerDestroy(producer: Long)
}
