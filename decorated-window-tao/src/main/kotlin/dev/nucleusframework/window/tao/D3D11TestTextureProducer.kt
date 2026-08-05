package dev.nucleusframework.window.tao

import dev.nucleusframework.core.runtime.Platform
import dev.nucleusframework.window.tao.ffi.NativeTaoTextureBridge

/**
 * Minimal D3D11 producer for demos and smoke tests of [TextureView]:
 * owns a standalone D3D11 device (hardware, WARP fallback) and a shared
 * `R8G8B8A8_UNORM` texture that [fill] clears to a solid colour. Real
 * applications plug their own producer (video decoder, GL/D3D renderer)
 * and only hand [TextureView] a [nucleusD3D11SharedTextureSource].
 *
 * With [useKeyedMutex][create] the texture carries a DXGI keyed mutex:
 * [fill] brackets its writes with `AcquireSync(0)`/`ReleaseSync(0)` and
 * [TextureView] switches to its tear-free staging path automatically.
 *
 * All methods are thread-safe: draw calls and [close] serialize on an
 * internal lock, so a producer loop on a background thread can never
 * race a dispose-time [close] into a native use-after-free. [close] is
 * idempotent; draw calls after it are no-ops.
 *
 * Windows only — [create] returns null elsewhere.
 */
public class D3D11TestTextureProducer private constructor(
    private val producer: Long,
    public val source: TextureViewSource,
) : AutoCloseable {
    private val lock = Any()
    private var closed = false

    /** Clears the shared texture to [argb] and flushes the producer device. */
    public fun fill(argb: Int) {
        synchronized(lock) {
            if (closed) return
            NativeTaoTextureBridge.nativeTestProducerFill(producer, argb)
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
            NativeTaoTextureBridge.nativeTestProducerDrawPattern(producer, tick, backgroundArgb)
        }
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            NativeTaoTextureBridge.nativeTestProducerDestroy(producer)
        }
    }

    public companion object {
        /** Returns null when not on Windows or when D3D11 is unavailable. */
        public fun create(
            widthPx: Int,
            heightPx: Int,
            useKeyedMutex: Boolean = false,
        ): D3D11TestTextureProducer? {
            if (Platform.Current != Platform.Windows || !NativeTaoTextureBridge.isLoaded) return null
            val producer = NativeTaoTextureBridge.nativeTestProducerCreate(widthPx, heightPx, useKeyedMutex)
            if (producer == 0L) return null
            val sharedHandle = NativeTaoTextureBridge.nativeTestProducerSharedHandle(producer)
            if (sharedHandle == 0L) {
                NativeTaoTextureBridge.nativeTestProducerDestroy(producer)
                return null
            }
            return D3D11TestTextureProducer(
                producer,
                nucleusD3D11SharedTextureSource(sharedHandle, widthPx, heightPx),
            )
        }
    }
}
