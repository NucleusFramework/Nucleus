package dev.nucleusframework.window.tao

import dev.nucleusframework.window.tao.ffi.NativeTaoEglBridge
import dev.nucleusframework.window.tao.ffi.NativeTaoLinuxTextureBridge
import org.jetbrains.skia.BackendTexture
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.DirectContext
import org.jetbrains.skia.FilterMipmap
import org.jetbrains.skia.FilterMode
import org.jetbrains.skia.GLAssembledInterface
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.MipmapMode
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface
import org.jetbrains.skia.SurfaceOrigin
import org.jetbrains.skia.makeGLWithInterface
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private const val TEX_W = 128
private const val TEX_H = 96
private const val GL_TEXTURE_2D = 0x0DE1
private const val GR_GL_RGBA8 = 0x8058
private val FILL_ARGB = 0xFF3366CC.toInt()
private val SECOND_FILL_ARGB = 0xFF1188EE.toInt()
private val WHITE_ARGB = 0xFFFFFFFF.toInt()

/**
 * Verifies the whole Linux `TextureView` GPU chain without a window, an event
 * loop or a display server: a GBM/EGL producer publishes a frame into a
 * DMA-BUF, the native bridge imports it as an `EGLImage`-backed GL texture on
 * the consumer's EGL context, Skia adopts it, and the frame is composited into
 * a GPU surface that is then read back — so the producer's pixels are proven to
 * reach the composited output.
 *
 * Two properties are pinned that only a GPU can answer:
 *  - a **second** producer frame shows up through the *same* Skia image, which
 *    is what "zero copy, no per-frame work" means on this backend (the Windows
 *    keyed-mutex path and macOS both re-pull a copy instead);
 *  - the import is oriented `TOP_LEFT` — the pattern's horizontal bar sits at
 *    the top of the composited image, not the bottom, despite GL's bottom-left
 *    framebuffer origin.
 *
 * Linux-only; skipped when no DRM render node / GBM / EGL DMA-BUF import is
 * available (headless CI without a GPU), the same signal production code
 * degrades on.
 */
class LinuxExternalTextureNativeSmokeTest {
    @Test
    fun dmaBufImportCompositesProducerPixels() {
        withGpu { producer, context ->
            val imported = assertNotNull(importProducer(producer, context), "import failed")
            val target =
                assertNotNull(
                    Surface.makeRenderTarget(context, false, ImageInfo.makeN32Premul(TEX_W, TEX_H)),
                    "GPU target surface creation failed",
                )

            // Two producer frames through one import: the texture *is* the
            // producer's buffer, so the second fill must show up with no
            // re-import and no copy.
            assertEquals(
                FILL_ARGB,
                compositeFill(producer, imported, target, FILL_ARGB),
                "composited pixel does not match the producer fill",
            )
            assertEquals(
                SECOND_FILL_ARGB,
                compositeFill(producer, imported, target, SECOND_FILL_ARGB),
                "second producer frame did not reach the composited surface",
            )

            target.close()
            imported.close()
        }
    }

    @Test
    fun dmaBufImportIsTopLeftOriented() {
        withGpu { producer, context ->
            val imported = assertNotNull(importProducer(producer, context), "import failed")
            val target =
                assertNotNull(
                    Surface.makeRenderTarget(context, false, ImageInfo.makeN32Premul(TEX_W, TEX_H)),
                    "GPU target surface creation failed",
                )

            // tick 0 puts the pattern's horizontal bar on the first rows of the
            // buffer and its vertical bar on the first columns.
            NativeTaoLinuxTextureBridge.nativeTestProducerDrawPattern(producer, 0, FILL_ARGB)
            val pixels = composite(imported, target)

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
     * The contract `withEglContextCurrent` relies on to bind another surface's
     * context from inside a live render pass without stealing it: the displaced
     * binding round-trips, and a nested snapshot is refused rather than
     * overwriting the outer one (which would lose it for good).
     */
    @Test
    fun bindingSnapshotRoundTripsAndRefusesNesting() {
        withGpu { producer, context ->
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
            val imported = assertNotNull(importProducer(producer, context), "import failed")
            val target =
                assertNotNull(
                    Surface.makeRenderTarget(context, false, ImageInfo.makeN32Premul(TEX_W, TEX_H)),
                    "GPU target surface creation failed",
                )
            assertEquals(
                FILL_ARGB,
                compositeFill(producer, imported, target, FILL_ARGB),
                "the restored context could not composite",
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
    private fun withGpu(block: (producer: Long, context: DirectContext) -> Unit) {
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
            val producer =
                NativeTaoLinuxTextureBridge.nativeTestProducerCreate(
                    TEX_W,
                    TEX_H,
                    NucleusDrmFormat.ARGB8888,
                )
            if (producer == 0L) {
                println("SKIP: GBM producer unavailable")
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
                block(producer, context)
            } finally {
                context.close()
                NativeTaoLinuxTextureBridge.nativeTestProducerDestroy(producer)
            }
        } finally {
            NativeTaoLinuxTextureBridge.nativeTestContextDestroy(eglContext)
        }
    }

    /** The import as `TextureViewLinux` builds it: EGLImage → GL texture → Skia. */
    private fun importProducer(
        producer: Long,
        context: DirectContext,
    ): ImportedTexture? {
        val fd = NativeTaoLinuxTextureBridge.nativeTestProducerFd(producer)
        val stride = NativeTaoLinuxTextureBridge.nativeTestProducerStride(producer)
        assertTrue(fd >= 0 && stride > 0, "producer DMA-BUF missing")
        val handle =
            NativeTaoLinuxTextureBridge.nativeImportDmaBuf(
                fd,
                NucleusDrmFormat.ARGB8888,
                TEX_W,
                TEX_H,
                stride,
                0,
                NativeTaoLinuxTextureBridge.nativeTestProducerModifier(producer),
            )
        assertTrue(handle > 0L, "DMA-BUF import failed with stage $handle")
        val texId = NativeTaoLinuxTextureBridge.nativeGlTextureId(handle)
        assertTrue(texId != 0, "imported GL texture missing")
        val image =
            runCatching {
                Image.adoptTextureFrom(
                    context,
                    BackendTexture.makeGL(TEX_W, TEX_H, false, texId, GL_TEXTURE_2D, GR_GL_RGBA8),
                    SurfaceOrigin.TOP_LEFT,
                    ColorType.RGBA_8888,
                )
            }.getOrNull()
        if (image == null) {
            NativeTaoLinuxTextureBridge.nativeDestroy(handle, deleteTexture = true)
            return null
        }
        return ImportedTexture(handle, image)
    }

    private class ImportedTexture(
        private val handle: Long,
        val image: Image,
    ) {
        fun close() {
            image.close()
            NativeTaoLinuxTextureBridge.nativeDestroy(handle, deleteTexture = false)
        }
    }

    /** Producer publishes [argb], consumer composites it; returns the centre pixel. */
    private fun compositeFill(
        producer: Long,
        imported: ImportedTexture,
        target: Surface,
        argb: Int,
    ): Int {
        NativeTaoLinuxTextureBridge.nativeTestProducerFill(producer, argb)
        return composite(imported, target).getColor(TEX_W / 2, TEX_H / 2)
    }

    /** One consumer frame: draw the import 1:1 into the GPU target and read it back. */
    private fun composite(
        imported: ImportedTexture,
        target: Surface,
    ): Bitmap {
        target.canvas.clear(0)
        target.canvas.drawImageRect(
            imported.image,
            Rect.makeWH(TEX_W.toFloat(), TEX_H.toFloat()),
            Rect.makeWH(TEX_W.toFloat(), TEX_H.toFloat()),
            FilterMipmap(FilterMode.NEAREST, MipmapMode.NONE),
            null,
            true,
        )
        target.flushAndSubmit(syncCpu = true)
        val bitmap = Bitmap().apply { allocN32Pixels(TEX_W, TEX_H) }
        assertTrue(target.readPixels(bitmap, 0, 0), "readback from the GPU target failed")
        return bitmap
    }
}
