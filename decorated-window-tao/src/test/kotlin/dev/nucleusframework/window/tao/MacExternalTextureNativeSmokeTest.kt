package dev.nucleusframework.window.tao

import dev.nucleusframework.window.tao.ffi.NativeMetalBridge
import dev.nucleusframework.window.tao.ffi.NativeTaoMacOsTextureBridge
import org.jetbrains.skia.BackendRenderTarget
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorSpace
import org.jetbrains.skia.ContentChangeMode
import org.jetbrains.skia.DirectContext
import org.jetbrains.skia.FilterMipmap
import org.jetbrains.skia.FilterMode
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.MipmapMode
import org.jetbrains.skia.PictureRecorder
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface
import org.jetbrains.skia.SurfaceColorFormat
import org.jetbrains.skia.SurfaceOrigin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private const val TEX_W = 64
private const val TEX_H = 48
private const val DEST_W = 32
private const val DEST_H = 24
private val FILL_ARGB = 0xFF3366CC.toInt()
private val SECOND_FILL_ARGB = 0xFF1188EE.toInt()

/**
 * Verifies the whole macOS `TextureView` GPU chain without a window or an event
 * loop: a Metal producer publishes a frame into an `IOSurface`, the native
 * bridge maps it onto the consumer's Metal device, Skia wraps it, and the frame
 * is composited through the same record-on-one-thread / replay-on-another split
 * the Tao host uses ([dev.nucleusframework.window.tao.scene.recordSceneToPicture]
 * → `replayPictureToFrame`). The readback then proves the producer's pixels
 * really landed in the composited surface.
 *
 * macOS-only; skipped when Metal is unavailable (headless CI without a GPU),
 * the same signal production code degrades on.
 */
class MacExternalTextureNativeSmokeTest {
    @Suppress("LongMethod")
    @Test
    fun ioSurfaceImportCompositesProducerPixels() {
        if (!System.getProperty("os.name", "").lowercase().contains("mac")) return
        assertTrue(NativeMetalBridge.isLoaded, "nucleus_tao_metal failed to load")
        assertTrue(NativeTaoMacOsTextureBridge.isLoaded, "texture bridge unavailable")

        val producer = NativeTaoMacOsTextureBridge.nativeTestProducerCreate(TEX_W, TEX_H)
        if (producer == 0L) {
            println("SKIP: no Metal device available")
            return
        }
        try {
            val ioSurface = NativeTaoMacOsTextureBridge.nativeTestProducerIoSurface(producer)
            assertTrue(ioSurface != 0L, "producer IOSurface missing")

            // Stand-in for the window's Metal attachment: the producer's device
            // and queue back the consumer's Skia context, so the import path is
            // exercised exactly as in a window (the IOSurface is the only bridge).
            val devicePtr = NativeTaoMacOsTextureBridge.nativeTestProducerDevicePtr(producer)
            val queuePtr = NativeTaoMacOsTextureBridge.nativeTestProducerQueuePtr(producer)
            assertTrue(devicePtr != 0L && queuePtr != 0L, "producer device/queue missing")

            val handle =
                NativeTaoMacOsTextureBridge.nativeImportIOSurface(devicePtr, ioSurface, TEX_W, TEX_H)
            assertTrue(handle > 0L, "IOSurface import failed with stage $handle")
            val texturePtr = NativeTaoMacOsTextureBridge.nativeTexturePtr(handle)
            assertTrue(texturePtr != 0L, "imported MTLTexture missing")
            assertEquals(
                NativeTaoMacOsTextureBridge.FORMAT_BGRA8,
                NativeTaoMacOsTextureBridge.nativePixelFormat(handle),
                "producer surface should import as BGRA8",
            )

            val context = assertNotNull(DirectContext.makeMetal(devicePtr, queuePtr), "no Metal context")
            val renderTarget = BackendRenderTarget.makeMetal(TEX_W, TEX_H, texturePtr)
            val external =
                assertNotNull(
                    Surface.makeFromBackendRenderTarget(
                        context = context,
                        rt = renderTarget,
                        origin = SurfaceOrigin.TOP_LEFT,
                        colorFormat = SurfaceColorFormat.BGRA_8888,
                        colorSpace = ColorSpace.sRGB,
                    ),
                    "Skia refused to wrap the imported MTLTexture",
                )

            val target =
                assertNotNull(
                    Surface.makeRenderTarget(
                        context,
                        false,
                        ImageInfo.makeN32Premul(DEST_W, DEST_H),
                    ),
                    "GPU target surface creation failed",
                )

            // Two producer frames in a row: the second one is what catches
            // Skia's cached snapshot (it has no idea the wrapped texture changed
            // behind its back — see TextureViewMac's notifyContentWillChange).
            assertEquals(
                FILL_ARGB,
                compositeOneFrame(producer, external, target, FILL_ARGB),
                "composited pixel does not match the producer fill",
            )
            assertEquals(
                SECOND_FILL_ARGB,
                compositeOneFrame(producer, external, target, SECOND_FILL_ARGB),
                "second producer frame did not reach the composited surface (stale snapshot)",
            )

            target.close()
            external.close()
            renderTarget.close()
            NativeTaoMacOsTextureBridge.nativeDestroy(handle)
            context.close()
        } finally {
            NativeTaoMacOsTextureBridge.nativeTestProducerDestroy(producer)
        }
    }

    /**
     * One full frame of the production pipeline: the producer fills the shared
     * surface, the consumer snapshots the import, records the draw into a
     * `Picture` (Compose's draw pass, main thread) and replays it into a GPU
     * surface (the render thread). Returns the composited centre pixel.
     */
    private fun compositeOneFrame(
        producer: Long,
        external: Surface,
        target: Surface,
        argb: Int,
    ): Int {
        NativeTaoMacOsTextureBridge.nativeTestProducerFill(producer, argb)

        external.notifyContentWillChange(ContentChangeMode.RETAIN)
        val frame = assertNotNull(external.makeImageSnapshot(), "snapshot of the import failed")
        val recorder = PictureRecorder()
        val canvas = recorder.beginRecording(Rect.makeWH(DEST_W.toFloat(), DEST_H.toFloat()))
        canvas.drawImageRect(
            frame,
            Rect.makeWH(TEX_W.toFloat(), TEX_H.toFloat()),
            Rect.makeWH(DEST_W.toFloat(), DEST_H.toFloat()),
            FilterMipmap(FilterMode.LINEAR, MipmapMode.NONE),
            null,
            true,
        )
        val picture = recorder.finishRecordingAsPicture()

        target.canvas.clear(0)
        target.canvas.drawPicture(picture)
        target.flushAndSubmit(syncCpu = true)

        val bitmap = Bitmap().apply { allocN32Pixels(DEST_W, DEST_H) }
        assertTrue(target.readPixels(bitmap, 0, 0), "readback from the GPU target failed")
        val color = bitmap.getColor(DEST_W / 2, DEST_H / 2)

        picture.close()
        frame.close()
        return color
    }
}
