package dev.nucleusframework.window.tao

import dev.nucleusframework.window.tao.ffi.NativeTaoEglBridge
import dev.nucleusframework.window.tao.ffi.NativeTaoLinuxTextureBridge
import dev.nucleusframework.window.tao.scene.TaoGlTextureHost
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.DirectContext
import org.jetbrains.skia.FilterMipmap
import org.jetbrains.skia.FilterMode
import org.jetbrains.skia.GLAssembledInterface
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.MipmapMode
import org.jetbrains.skia.Rect
import org.jetbrains.skia.SamplingMode
import org.jetbrains.skia.Surface
import org.jetbrains.skia.makeGLWithInterface
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private const val TEX_W = 128
private const val TEX_H = 96
private val FILL_ARGB = 0xFF3366CC.toInt()
private val SECOND_FILL_ARGB = 0xFF1188EE.toInt()
private val WHITE_ARGB = 0xFFFFFFFF.toInt()

/** 8-bit Y'CbCr at 4:2:0 cannot round-trip a colour exactly; ±4 per channel covers it. */
private const val YUV_TOLERANCE = 4

private val NEAREST = FilterMipmap(FilterMode.NEAREST, MipmapMode.NONE)

/**
 * Verifies the whole Linux `TextureView` GPU chain without a window, an event
 * loop or a display server: a GBM/EGL producer publishes a frame into a
 * DMA-BUF, the production importer maps it onto the consumer's EGL context as
 * Skia images, and the frame is drawn into a GPU surface that is then read back —
 * so the producer's pixels are proven to reach the composited output. The import
 * and the draw are the very functions the composable calls, so the planar
 * shader and the packed fast path are both covered as shipped.
 *
 * Properties pinned here that only a GPU can answer:
 *  - a **second** producer frame shows up through the *same* Skia image, which
 *    is what "zero copy, no per-frame work" means on this backend (the Windows
 *    keyed-mutex path and macOS both re-pull a copy instead);
 *  - the import is oriented `TOP_LEFT` — the pattern's horizontal bar sits at
 *    the top of the composited image, not the bottom, despite GL's bottom-left
 *    framebuffer origin;
 *  - a planar (NV12 / I420) frame composites back as the colour it was published
 *    as, which is what proves the plane FourCCs, the chroma subsampling and the
 *    conversion matrix all agree with the producer's;
 *  - an acquire fence is accepted, waited on, and yields the producer's pixels
 *    without the producer having finished its writes on the CPU.
 *
 * Linux-only; skipped when no DRM render node / GBM / EGL DMA-BUF import is
 * available (headless CI without a GPU), the same signal production code
 * degrades on.
 */
class LinuxExternalTextureNativeSmokeTest {
    @Test
    fun dmaBufImportCompositesProducerPixels() {
        withGpu { producer, host ->
            val imported = assertNotNull(importLinuxTexture(host, producer.source), "import failed")
            val target = gpuTarget(host.directContext)

            // Two producer frames through one import: the texture *is* the
            // producer's buffer, so the second fill must show up with no
            // re-import and no copy.
            val controller = TextureViewController()
            assertEquals(
                FILL_ARGB,
                compositeFill(producer, imported, target, FILL_ARGB, controller),
                "composited pixel does not match the producer fill",
            )
            assertEquals(
                SECOND_FILL_ARGB,
                compositeFill(producer, imported, target, SECOND_FILL_ARGB, controller),
                "second producer frame did not reach the composited surface",
            )

            target.close()
            imported.close()
        }
    }

    @Test
    fun dmaBufImportIsTopLeftOriented() {
        withGpu { producer, host ->
            val imported = assertNotNull(importLinuxTexture(host, producer.source), "import failed")
            val target = gpuTarget(host.directContext)

            // tick 0 puts the pattern's horizontal bar on the first rows of the
            // buffer and its vertical bar on the first columns.
            producer.drawTestPattern(0, FILL_ARGB)
            val pixels = composite(imported, target, controller = null)

            assertEquals(WHITE_ARGB, pixels.getColor(TEX_W / 2, 2), "top rows should carry the bar")
            assertEquals(
                FILL_ARGB,
                pixels.getColor(TEX_W / 2, TEX_H - 3),
                "bottom rows should carry the background; a flipped import would put the bar here",
            )

            target.close()
            imported.close()
        }
    }

    /**
     * A planar frame has to come back the colour it went in as: the plane
     * FourCCs, the half-resolution chroma sampling and the conversion matrix are
     * all only right *together*, and any one of them being wrong shows up as a
     * hue shift rather than as a failure anywhere else.
     */
    @Test
    fun i420ImportCompositesProducerColour() {
        assertPlanarRoundTrip(NucleusYuvFormat.I420)
    }

    /**
     * `YV12` is the same buffer with its chroma planes listed the other way round,
     * so it has to composite to the same colour — which is what pins the plane
     * reordering the importer does for it. A missing swap turns this colour
     * (Cb 178, Cr 101) into a visibly different one.
     */
    @Test
    fun yv12ImportCompositesProducerColour() {
        assertPlanarRoundTrip(NucleusYuvFormat.YV12)
    }

    /**
     * Full-range BT.601 as well, because the range and the matrix enter the
     * shader as separate uniforms and a swap between them is invisible on greys.
     */
    @Test
    fun i420FullRangeImportCompositesProducerColour() {
        assertPlanarRoundTrip(NucleusYuvFormat.I420, NucleusYuvColorSpace.BT601_FULL)
    }

    /** The planar path is oriented like the packed one, chroma planes included. */
    @Test
    fun i420ImportIsTopLeftOriented() {
        withGpu(producerFactory = { DmaBufTestTextureProducer.createYuv(TEX_W, TEX_H) }) { producer, host ->
            val imported = assertNotNull(importLinuxTexture(host, producer.source), "I420 import failed")
            val target = gpuTarget(host.directContext)

            producer.drawTestPattern(0, FILL_ARGB)
            val pixels = composite(imported, target, controller = null)

            assertColorNear(WHITE_ARGB, pixels.getColor(TEX_W / 2, 2), "top rows should carry the bar")
            assertColorNear(
                FILL_ARGB,
                pixels.getColor(TEX_W / 2, TEX_H - 3),
                "bottom rows should carry the background; a flipped plane would put the bar here",
            )

            target.close()
            imported.close()
        }
    }

    /**
     * The fence path end to end: the producer publishes without `glFinish` and
     * hands over a `sync_file`, the controller takes ownership of it, and the
     * import waits on it before the draw that samples the frame. What this pins is
     * that the wait is accepted and ordered — a fence the driver refused would
     * leave `nativeWaitFence` returning false, and a fence waited on *after* the
     * draw would not compose reliably at all.
     */
    @Test
    fun acquireFenceIsWaitedOnBeforeSampling() {
        withGpu { producer, host ->
            if (!NativeTaoLinuxTextureBridge.nativeIsNativeFenceSupported()) {
                println("SKIP: driver lacks EGL_ANDROID_native_fence_sync")
                return@withGpu
            }
            val imported = assertNotNull(importLinuxTexture(host, producer.source), "import failed")
            val target = gpuTarget(host.directContext)
            val controller = TextureViewController()

            val fenceFd = producer.drawTestPatternFenced(0, FILL_ARGB)
            assertTrue(fenceFd >= 0, "producer published no fence on a driver that has them")
            controller.markFrameAvailable(fenceFd)
            assertTrue(controller.hasAcquireFence, "the controller did not take the fence")

            val pixels = composite(imported, target, controller)
            assertEquals(WHITE_ARGB, pixels.getColor(TEX_W / 2, 2), "fenced frame did not composite")

            // Drawing the same frame again is a no-op: the fence is waited on once
            // per producer frame per import, like every other per-frame pull.
            composite(imported, target, controller)
            controller.releaseAcquireFence()
            assertTrue(!controller.hasAcquireFence, "the fence outlived its release")

            target.close()
            imported.close()
        }
    }

    /**
     * The contract `withEglContextCurrent` relies on to bind another surface's
     * context from inside a live render pass without stealing it: the displaced
     * binding round-trips, and a nested snapshot is refused rather than
     * overwriting the outer one (which would lose it for good).
     */
    @Test
    fun bindingSnapshotRoundTripsAndRefusesNesting() {
        withGpu { producer, host ->
            assertTrue(NativeTaoLinuxTextureBridge.nativeSaveCurrentBinding(), "snapshot refused")
            assertTrue(
                !NativeTaoLinuxTextureBridge.nativeSaveCurrentBinding(),
                "a nested snapshot must be refused, not overwrite the outstanding one",
            )
            assertTrue(NativeTaoLinuxTextureBridge.nativeRestoreBinding(), "restore lost the binding")
            assertTrue(
                !NativeTaoLinuxTextureBridge.nativeRestoreBinding(),
                "restoring twice must report that nothing was saved",
            )

            // The context really is back: compositing needs it current, and the
            // producer's own bind/restore has to have left it alone too.
            val imported = assertNotNull(importLinuxTexture(host, producer.source), "import failed")
            val target = gpuTarget(host.directContext)
            assertEquals(
                FILL_ARGB,
                compositeFill(producer, imported, target, FILL_ARGB, TextureViewController()),
                "the restored context could not composite",
            )
            target.close()
            imported.close()
        }
    }

    private fun assertPlanarRoundTrip(
        format: NucleusYuvFormat,
        colorSpace: NucleusYuvColorSpace = NucleusYuvColorSpace.BT709_LIMITED,
    ) {
        withGpu(
            producerFactory = { DmaBufTestTextureProducer.createYuv(TEX_W, TEX_H, format, colorSpace) },
            unavailable = "$format producer unavailable (driver cannot render to a planar buffer?)",
        ) { producer, host ->
            val imported = assertNotNull(importLinuxTexture(host, producer.source), "$format import failed")
            val target = gpuTarget(host.directContext)

            val controller = TextureViewController()
            assertColorNear(
                FILL_ARGB,
                compositeFill(producer, imported, target, FILL_ARGB, controller),
                "$format frame did not composite as the colour it was published as",
            )
            // A second frame through the same planes and the same shader, as on
            // the packed path: still no re-import and no copy.
            assertColorNear(
                SECOND_FILL_ARGB,
                compositeFill(producer, imported, target, SECOND_FILL_ARGB, controller),
                "second $format frame did not reach the composited surface",
            )

            target.close()
            imported.close()
        }
    }

    /**
     * Brings up the headless consumer context, a Skia context on it and a
     * producer, runs [block], then tears everything down. Returns without
     * running anything when the machine has no usable GPU stack — the
     * production code degrades to an empty `Box` in exactly the same cases.
     */
    private fun withGpu(
        producerFactory: () -> DmaBufTestTextureProducer? = { DmaBufTestTextureProducer.create(TEX_W, TEX_H) },
        unavailable: String = "GBM producer unavailable",
        block: (producer: DmaBufTestTextureProducer, host: TaoGlTextureHost) -> Unit,
    ) {
        if (!System.getProperty("os.name", "").lowercase().contains("linux")) return
        assertTrue(NativeTaoEglBridge.isLoaded, "nucleus_tao_egl failed to load")
        assertTrue(NativeTaoLinuxTextureBridge.isLoaded, "texture bridge unavailable")

        val eglContext = NativeTaoLinuxTextureBridge.nativeTestContextCreate()
        if (eglContext == 0L) {
            println("SKIP: no GBM/EGL context available (no DRM render node?)")
            return
        }
        try {
            if (!NativeTaoLinuxTextureBridge.nativeIsDmaBufImportSupported()) {
                println("SKIP: driver lacks EGL_EXT_image_dma_buf_import")
                return
            }
            val producer = producerFactory()
            if (producer == null) {
                println("SKIP: $unavailable")
                return
            }
            val fnPtr = NativeTaoEglBridge.nativeGetProcAddrFunctionPointer()
            assertTrue(fnPtr != 0L, "eglGetProcAddress unavailable")
            val context =
                assertNotNull(
                    runCatching {
                        DirectContext.makeGLWithInterface(
                            GLAssembledInterface.createFromNativePointers(0L, fnPtr),
                        )
                    }.getOrNull(),
                    "Skia refused the headless GL context",
                )
            try {
                block(producer, HeadlessGlTextureHost(context))
            } finally {
                context.close()
                producer.close()
            }
        } finally {
            NativeTaoLinuxTextureBridge.nativeTestContextDestroy(eglContext)
        }
    }

    /**
     * The surface the importer draws into, in the role a Compose scene plays: the
     * test context is current on this thread for the whole run, so
     * [withContextCurrent] simply runs its block.
     */
    private class HeadlessGlTextureHost(
        override val directContext: DirectContext,
    ) : TaoGlTextureHost {
        override fun <T> withContextCurrent(block: () -> T): T? = block()
    }

    private fun gpuTarget(context: DirectContext): Surface =
        assertNotNull(
            Surface.makeRenderTarget(context, false, ImageInfo.makeN32Premul(TEX_W, TEX_H)),
            "GPU target surface creation failed",
        )

    /**
     * Producer publishes [argb] and signals it, consumer composites it; returns the
     * centre pixel. Signalling matters: it is what tells the import a new producer
     * frame is there, which the planar routes with per-frame work act on.
     */
    private fun compositeFill(
        producer: DmaBufTestTextureProducer,
        imported: LinuxImportedTexture,
        target: Surface,
        argb: Int,
        controller: TextureViewController,
    ): Int {
        producer.fill(argb)
        controller.markFrameAvailable()
        return composite(imported, target, controller).getColor(TEX_W / 2, TEX_H / 2)
    }

    /**
     * One consumer frame, exactly as the composable runs it: the draw pass first
     * does its per-frame work (acquire fence, planar repack) and then draws the
     * import 1:1 into the GPU target, which is read back.
     */
    private fun composite(
        imported: LinuxImportedTexture,
        target: Surface,
        controller: TextureViewController?,
        sampling: SamplingMode = NEAREST,
    ): Bitmap {
        target.canvas.clear(0)
        imported.onDrawPass(controller, controller?.frameStamp?.longValue ?: 0L)
        imported.draw(target.canvas, Rect.makeWH(TEX_W.toFloat(), TEX_H.toFloat()), sampling)
        target.flushAndSubmit(syncCpu = true)
        val bitmap = Bitmap().apply { allocN32Pixels(TEX_W, TEX_H) }
        assertTrue(target.readPixels(bitmap, 0, 0), "readback from the GPU target failed")
        return bitmap
    }

    private fun assertColorNear(
        expected: Int,
        actual: Int,
        message: String,
    ) {
        val deltas =
            listOf(24, 16, 8, 0).map { shift ->
                abs(((expected shr shift) and 0xFF) - ((actual shr shift) and 0xFF))
            }
        assertTrue(
            deltas.all { it <= YUV_TOLERANCE },
            "$message: expected ~${expected.toUInt().toString(16)}, got ${actual.toUInt().toString(16)}",
        )
    }
}
