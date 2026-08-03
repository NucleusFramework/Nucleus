package dev.nucleusframework.window.tao

import dev.nucleusframework.core.runtime.Platform
import dev.nucleusframework.window.tao.ffi.NativeTaoMacOsTextureBridge

/**
 * Minimal Metal producer for demos and smoke tests of [TextureView] on macOS:
 * owns a private `MTLDevice` + command queue and a `BGRA` `IOSurface` wrapped as
 * a render target, which [fill] clears to a solid colour. The `IOSurface` is the
 * only object shared with the compositor — exactly how a real producer (video
 * decoder, offscreen renderer, another process) publishes frames. Applications
 * plug their own producer and only hand [TextureView] a
 * [nucleusIOSurfaceTextureSource].
 *
 * Both draw calls commit and wait for GPU completion before returning, so the
 * frame is fully written by the time the caller signals
 * [TextureViewController.markFrameAvailable] — the contract that makes the
 * compositor's per-frame copy tear-free.
 *
 * All methods are thread-safe: draw calls and [close] serialize on an internal
 * lock, so a producer loop on a background thread can never race a dispose-time
 * [close] into a native use-after-free. [close] is idempotent; draw calls after
 * it are no-ops.
 *
 * macOS only — [create] returns null elsewhere. The Windows counterpart is
 * [D3D11TestTextureProducer].
 */
public class MetalTestTextureProducer private constructor(
    private val producer: Long,
    public val source: TextureViewSource,
) : AutoCloseable {
    private val lock = Any()
    private var closed = false

    /** Clears the shared surface to [argb] and waits for the GPU. */
    public fun fill(argb: Int) {
        synchronized(lock) {
            if (closed) return
            NativeTaoMacOsTextureBridge.nativeTestProducerFill(producer, argb)
        }
    }

    /**
     * Draws an animated test pattern: [backgroundArgb] background plus two
     * moving white bars driven by [tick] — gives contentScale/filterQuality
     * demos some structure and makes tearing observable.
     */
    public fun drawTestPattern(
        tick: Int,
        backgroundArgb: Int,
    ) {
        synchronized(lock) {
            if (closed) return
            NativeTaoMacOsTextureBridge.nativeTestProducerDrawPattern(producer, tick, backgroundArgb)
        }
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            NativeTaoMacOsTextureBridge.nativeTestProducerDestroy(producer)
        }
    }

    public companion object {
        /** Returns null when not on macOS or when Metal is unavailable. */
        public fun create(
            widthPx: Int,
            heightPx: Int,
        ): MetalTestTextureProducer? {
            if (Platform.Current != Platform.MacOS || !NativeTaoMacOsTextureBridge.isLoaded) return null
            val producer = NativeTaoMacOsTextureBridge.nativeTestProducerCreate(widthPx, heightPx)
            if (producer == 0L) return null
            val ioSurface = NativeTaoMacOsTextureBridge.nativeTestProducerIoSurface(producer)
            if (ioSurface == 0L) {
                NativeTaoMacOsTextureBridge.nativeTestProducerDestroy(producer)
                return null
            }
            return MetalTestTextureProducer(
                producer,
                nucleusIOSurfaceTextureSource(ioSurface, widthPx, heightPx),
            )
        }
    }
}
