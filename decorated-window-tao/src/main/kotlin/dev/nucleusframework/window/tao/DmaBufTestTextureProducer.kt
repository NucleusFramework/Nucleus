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
 * [create] allocates a packed RGB buffer; [createYuv] a planar one (NV12 or
 * I420), which is what a hardware decoder hands out — there each plane is a
 * render target of its own, and the pattern is converted to Y'CbCr on the way in.
 *
 * [drawTestPattern] and [fill] `glFinish` before returning, so the frame is fully
 * written by the time the caller signals
 * [TextureViewController.markFrameAvailable] — the contract that keeps zero-copy
 * sampling tear-free. [drawTestPatternFenced] is the other half of that choice:
 * it returns an acquire fence instead of blocking.
 *
 * All methods are thread-safe: draw calls and [close] serialize on an internal
 * lock and the producer binds its EGL context per call, so a producer loop on a
 * background dispatcher (whose thread may change between frames) is safe and can
 * never race a dispose-time [close] into a native use-after-free. [close] is
 * idempotent; draw calls after it are no-ops.
 *
 * Linux only — the factories return null elsewhere, and also when no render node,
 * GBM or EGL DMA-BUF support is available. The Windows and macOS counterparts
 * are [D3D11TestTextureProducer] and [MetalTestTextureProducer].
 */
public class DmaBufTestTextureProducer private constructor(
    private val producer: Long,
    /**
     * Source to hand [TextureView]. **Only valid while this producer is open**:
     * [close] closes the underlying DMA-BUF fds, and the source carries those raw
     * fds, which the OS is then free to recycle for something else. Drop the
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

    /**
     * Same pattern, published with an **acquire fence** instead of a `glFinish`:
     * returns the fence descriptor to pass straight to
     * [TextureViewController.markFrameAvailable], which takes ownership of it.
     *
     * Returns [TextureViewController.NO_FENCE] when the driver has no
     * `EGL_ANDROID_native_fence_sync` — the frame was then finished synchronously,
     * so it is safe to signal either way, which is exactly how a real producer
     * should treat this: the fence is an optimisation, not a protocol.
     */
    public fun drawTestPatternFenced(
        tick: Int,
        backgroundArgb: Int,
    ): Int =
        synchronized(lock) {
            if (closed) {
                TextureViewController.NO_FENCE
            } else {
                NativeTaoLinuxTextureBridge.nativeTestProducerDrawPatternFenced(
                    producer,
                    tick,
                    backgroundArgb,
                )
            }
        }

    /** Closes the GBM/EGL resources **and the DMA-BUF fds** — see [source]. */
    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            NativeTaoLinuxTextureBridge.nativeTestProducerDestroy(producer)
        }
    }

    public companion object {
        /**
         * Packed RGB producer. Returns null when not on Linux, when
         * `libnucleus_tao_egl` is missing, or when no GBM render node can back it.
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
            val producer =
                newProducer {
                    NativeTaoLinuxTextureBridge.nativeTestProducerCreate(widthPx, heightPx, fourcc)
                } ?: return null
            val plane = planeOf(producer, 0) ?: return closeAndFail(producer)
            return DmaBufTestTextureProducer(
                producer,
                nucleusDmaBufTextureSource(
                    fd = plane.fd,
                    widthPx = widthPx,
                    heightPx = heightPx,
                    stride = plane.stride,
                    fourcc = fourcc,
                    offset = plane.offset,
                    modifier = plane.modifier,
                ),
            )
        }

        /**
         * Planar producer: one `I420` GBM buffer, each plane a render target of its
         * own, the pattern converted to Y'CbCr in [colorSpace] with the exact
         * inverse of the matrix [TextureView] converts back with.
         *
         * [widthPx] and [heightPx] must be even. The buffer is always allocated as
         * `I420`; asking for [NucleusYuvFormat.YV12] describes that same buffer with
         * its chroma planes listed the other way round, which is exactly what a
         * `YV12` producer hands over — so both formats are the real thing here.
         * Returns null when the driver cannot render to a planar buffer.
         */
        public fun createYuv(
            widthPx: Int,
            heightPx: Int,
            format: NucleusYuvFormat = NucleusYuvFormat.I420,
            colorSpace: NucleusYuvColorSpace = NucleusYuvColorSpace.BT709_LIMITED,
        ): DmaBufTestTextureProducer? {
            val producer =
                newProducer {
                    NativeTaoLinuxTextureBridge.nativeTestProducerCreateYuv(
                        widthPx,
                        heightPx,
                        NucleusYuvFormat.I420.ordinal,
                        colorSpace.ordinal,
                    )
                } ?: return null
            val planes = (0 until format.planeCount).map { planeOf(producer, it) ?: return closeAndFail(producer) }
            return DmaBufTestTextureProducer(
                producer,
                nucleusYuvDmaBufTextureSource(
                    widthPx = widthPx,
                    heightPx = heightPx,
                    format = format,
                    // Y, Cb, Cr as allocated — which is Y, Cr, Cb read as YV12.
                    planes =
                        if (format == NucleusYuvFormat.YV12) {
                            listOf(planes[0], planes[2], planes[1])
                        } else {
                            planes
                        },
                    colorSpace = colorSpace,
                ),
            )
        }

        private inline fun newProducer(create: () -> Long): Long? {
            if (Platform.Current != Platform.Linux || !NativeTaoLinuxTextureBridge.isLoaded) return null
            return create().takeIf { it != 0L }
        }

        /** Geometry of plane [index] as the importer needs it, or null if unusable. */
        private fun planeOf(
            producer: Long,
            index: Int,
        ): NucleusDmaBufPlane? {
            val fd = NativeTaoLinuxTextureBridge.nativeTestProducerPlaneFd(producer, index)
            val stride = NativeTaoLinuxTextureBridge.nativeTestProducerPlaneStride(producer, index)
            if (fd < 0 || stride < 1) return null
            return NucleusDmaBufPlane(
                fd = fd,
                stride = stride,
                offset = NativeTaoLinuxTextureBridge.nativeTestProducerPlaneOffset(producer, index),
                modifier = NativeTaoLinuxTextureBridge.nativeTestProducerPlaneModifier(producer, index),
            )
        }

        private fun closeAndFail(producer: Long): DmaBufTestTextureProducer? {
            NativeTaoLinuxTextureBridge.nativeTestProducerDestroy(producer)
            return null
        }
    }
}
