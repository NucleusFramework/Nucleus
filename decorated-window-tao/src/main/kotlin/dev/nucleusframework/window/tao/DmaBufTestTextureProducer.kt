package dev.nucleusframework.window.tao

import dev.nucleusframework.core.runtime.Platform
import dev.nucleusframework.window.tao.ffi.NativeTaoLinuxTextureBridge

/**
 * Minimal DMA-BUF producer for demos and smoke tests of [TextureView] on Linux:
 * owns a private GBM device (first usable `/dev/dri/renderD*`), a render buffer
 * exported as a DMA-BUF, and its own EGL display + surfaceless context with that
 * buffer bound as an FBO colour attachment. The DMA-BUF is the only thing shared
 * with the compositor — exactly how a real producer (video decoder, offscreen
 * renderer, another process) publishes frames. Applications plug their own
 * producer and only hand [TextureView] a [nucleusDmaBufTextureSource].
 *
 * Both draw calls `glFinish` before returning, so the frame is fully written by
 * the time the caller signals [TextureViewController.markFrameAvailable] — the
 * contract that keeps zero-copy sampling tear-free.
 *
 * All methods are thread-safe: draw calls and [close] serialize on an internal
 * lock and the producer binds its EGL context per call, so a producer loop on a
 * background dispatcher (whose thread may change between frames) is safe and can
 * never race a dispose-time [close] into a native use-after-free. [close] is
 * idempotent; draw calls after it are no-ops.
 *
 * Linux only — [create] returns null elsewhere, and also when no render node,
 * GBM or EGL DMA-BUF support is available. The Windows and macOS counterparts
 * are [D3D11TestTextureProducer] and [MetalTestTextureProducer].
 */
public class DmaBufTestTextureProducer private constructor(
    private val producer: Long,
    /**
     * Source to hand [TextureView]. **Only valid while this producer is open**:
     * [close] closes the underlying DMA-BUF fd, and the source carries that raw
     * fd, which the OS is then free to recycle for something else. Drop the
     * reference along with the producer — importing it afterwards would hand
     * `eglCreateImageKHR` a foreign descriptor.
     */
    public val source: TextureViewSource,
) : AutoCloseable {
    private val lock = Any()
    private var closed = false

    /** Clears the shared buffer to [argb] and waits for the GPU. */
    public fun fill(argb: Int) {
        synchronized(lock) {
            if (closed) return
            NativeTaoLinuxTextureBridge.nativeTestProducerFill(producer, argb)
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
            NativeTaoLinuxTextureBridge.nativeTestProducerDrawPattern(producer, tick, backgroundArgb)
        }
    }

    /** Closes the GBM/EGL resources **and the DMA-BUF fd** — see [source]. */
    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            NativeTaoLinuxTextureBridge.nativeTestProducerDestroy(producer)
        }
    }

    public companion object {
        /**
         * Returns null when not on Linux, when `libnucleus_tao_egl` is missing,
         * or when no GBM render node can back the producer.
         *
         * [fourcc] defaults to [NucleusDrmFormat.ARGB8888] — GBM's and Wayland's
         * usual layout; pass [NucleusDrmFormat.ABGR8888] to check that channel
         * order really is the driver's business (both must look identical).
         */
        public fun create(
            widthPx: Int,
            heightPx: Int,
            fourcc: Int = NucleusDrmFormat.ARGB8888,
        ): DmaBufTestTextureProducer? {
            if (Platform.Current != Platform.Linux || !NativeTaoLinuxTextureBridge.isLoaded) return null
            val producer = NativeTaoLinuxTextureBridge.nativeTestProducerCreate(widthPx, heightPx, fourcc)
            if (producer == 0L) return null
            val fd = NativeTaoLinuxTextureBridge.nativeTestProducerFd(producer)
            val stride = NativeTaoLinuxTextureBridge.nativeTestProducerStride(producer)
            if (fd < 0 || stride < 1) {
                NativeTaoLinuxTextureBridge.nativeTestProducerDestroy(producer)
                return null
            }
            return DmaBufTestTextureProducer(
                producer,
                nucleusDmaBufTextureSource(
                    fd = fd,
                    widthPx = widthPx,
                    heightPx = heightPx,
                    stride = stride,
                    fourcc = fourcc,
                    modifier = NativeTaoLinuxTextureBridge.nativeTestProducerModifier(producer),
                ),
            )
        }
    }
}
