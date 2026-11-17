package dev.nucleusframework.tray.utils

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Regression coverage for #436: `forCurrentOperatingSystem()` / `forMenuItem()` used to
 * downsample the 192px (resp. 64px) Compose scene to a single physical pixel size
 * (Windows 32, macOS 44, Linux 24; menu items 16) before the native layer ever saw the
 * bitmap. High-DPI information was destroyed on the JVM.
 *
 * Expected:
 *  - BEFORE the fix: PNG is 24/32/44 (tray) or 16 (menu); ICO has a single frame.
 *  - AFTER the fix: PNG keeps the scene-resolution master; ICO is a DPI pyramid
 *    downsampled from that master, never an upscale.
 */
class Issue436IconScalingTest {
    @Test
    fun `forCurrentOperatingSystem does not ask the JVM to crush the scene master`() {
        val props = IconRenderProperties.forCurrentOperatingSystem()
        assertFalse(
            props.jvmOwnsDownscaling,
            "forCurrentOperatingSystem() must not downsample on the JVM",
        )
        assertFalse(
            props.requiresScaling,
            "forCurrentOperatingSystem() must leave downscaling to native backends " +
                "(requiresScaling=${props.requiresScaling}, " +
                "scene=${props.sceneWidth}x${props.sceneHeight}, " +
                "target=${props.targetWidth}x${props.targetHeight})",
        )
    }

    @Test
    fun `forMenuItem does not ask the JVM to crush the scene master`() {
        val props = IconRenderProperties.forMenuItem()
        assertFalse(
            props.jvmOwnsDownscaling,
            "forMenuItem() must not downsample on the JVM",
        )
        assertFalse(
            props.requiresScaling,
            "forMenuItem() must leave downscaling to native backends " +
                "(requiresScaling=${props.requiresScaling}, " +
                "scene=${props.sceneWidth}x${props.sceneHeight}, " +
                "target=${props.targetWidth}x${props.targetHeight})",
        )
    }

    @Test
    fun `ico pyramid never upscales past the master`() {
        assertEquals(listOf(16, 20, 24, 32, 40, 48, 64), icoFrameSizesFor(192, 192))
        assertEquals(listOf(16, 20, 24), icoFrameSizesFor(24, 24))
        assertEquals(listOf(8), icoFrameSizesFor(8, 8))
    }

    @Test
    fun `ico container writes one directory entry per frame`() {
        val png = MINIMAL_PNG
        val ico = packPngFramesAsIco(listOf(16 to png, 32 to png, 64 to png))
        assertEquals(listOf(16, 32, 64), icoFrames(ico).map { it.size })
    }

    @Test
    fun `tray png keeps the scene-resolution master instead of a fixed 24-32-44 px icon`() {
        val props = IconRenderProperties.forCurrentOperatingSystem()
        val png = renderSolid(props)
        val (width, height) = pngSize(png)
        assertEquals(
            props.sceneWidth,
            width,
            "tray PNG width must be the scene master, not the OS physical target " +
                "(got ${width}x$height, scene=${props.sceneWidth}, target=${props.targetWidth})",
        )
        assertEquals(props.sceneHeight, height)
        assertTrue(
            width >= 128 && height >= 128,
            "tray master must be large enough for the Linux 128px SNI pyramid and 3x Retina " +
                "(got ${width}x$height)",
        )
    }

    @Test
    fun `menu-item png keeps the scene-resolution master instead of 16 px`() {
        val props = IconRenderProperties.forMenuItem()
        val png = renderSolid(props)
        val (width, height) = pngSize(png)
        assertEquals(
            props.sceneWidth,
            width,
            "menu PNG width must be the scene master, not 16px " +
                "(got ${width}x$height, scene=${props.sceneWidth}, target=${props.targetWidth})",
        )
        assertEquals(props.sceneHeight, height)
        assertTrue(
            width >= 48 && height >= 48,
            "menu master must cover 16pt at ≥3x (got ${width}x$height)",
        )
    }

    @Test
    fun `windows ico is a dpi pyramid downsampled from the master not a single 32px frame`() {
        val props = IconRenderProperties.forCurrentOperatingSystem()
        val ico = ComposableIconUtils.renderComposableToIcoBytes(props) { SolidRedIcon() }
        val frames = icoFrames(ico)

        assertTrue(
            frames.size > 1,
            "ICO must contain multiple DPI frames so the shell can pick an exact match, " +
                "got ${frames.map { it.size }}",
        )
        val sizes = frames.map { it.size }
        assertTrue(16 in sizes, "ICO is missing the 16px (100% scale) frame: $sizes")
        assertTrue(32 in sizes, "ICO is missing the 32px (200% scale) frame: $sizes")
        assertTrue(
            sizes.max() >= 64,
            "ICO must include at least a 64px frame for 200%+ / SM_CXSMICON at high DPI, " +
                "got $sizes",
        )
        assertTrue(
            sizes.all { it <= props.sceneWidth && it <= props.sceneHeight },
            "ICO must never upscale past the scene master (scene=${props.sceneWidth}, frames=$sizes)",
        )
        frames.forEach { frame ->
            assertEquals(
                frame.size,
                frame.pngWidth,
                "ICO directory size ${frame.size} does not match PNG IHDR ${frame.pngWidth}",
            )
            assertEquals(frame.size, frame.pngHeight)
        }
    }

    @Test
    fun `explicit jvm target still downsamples when the caller opts in`() {
        val props =
            IconRenderProperties(
                sceneWidth = 64,
                sceneHeight = 64,
                targetWidth = 16,
                targetHeight = 16,
            )
        assertTrue(props.requiresScaling, "explicit target != scene must still scale on the JVM")
        val png = renderSolid(props)
        val (width, height) = pngSize(png)
        assertEquals(16, width)
        assertEquals(16, height)
    }

    private fun renderSolid(props: IconRenderProperties): ByteArray =
        try {
            ComposableIconUtils.renderComposableToPngBytes(props) { SolidRedIcon() }
        } catch (t: Throwable) {
            fail("ImageComposeScene failed to render the icon master: ${t.message}")
        }

    private fun pngSize(png: ByteArray): Pair<Int, Int> {
        require(png.size >= 24) { "PNG too short: ${png.size}" }
        require(png[0] == 0x89.toByte() && png[1] == 0x50.toByte()) { "not a PNG" }
        val buf = ByteBuffer.wrap(png, 16, 8).order(ByteOrder.BIG_ENDIAN)
        return buf.int to buf.int
    }

    private data class IcoFrame(
        val size: Int,
        val pngWidth: Int,
        val pngHeight: Int,
    )

    private fun icoFrames(ico: ByteArray): List<IcoFrame> {
        require(ico.size >= 6) { "ICO too short: ${ico.size}" }
        require(ico[2].toInt() and 0xFF == 1) { "not an ICO (type=${ico[2]})" }
        val count = ico[4].toInt() and 0xFF
        return (0 until count).map { i ->
            val entry = 6 + i * 16
            val dirSize = ico[entry].toInt() and 0xFF
            val size = if (dirSize == 0) 256 else dirSize
            val dataSize =
                (ico[entry + 8].toInt() and 0xFF) or
                    ((ico[entry + 9].toInt() and 0xFF) shl 8) or
                    ((ico[entry + 10].toInt() and 0xFF) shl 16) or
                    ((ico[entry + 11].toInt() and 0xFF) shl 24)
            val offset =
                (ico[entry + 12].toInt() and 0xFF) or
                    ((ico[entry + 13].toInt() and 0xFF) shl 8) or
                    ((ico[entry + 14].toInt() and 0xFF) shl 16) or
                    ((ico[entry + 15].toInt() and 0xFF) shl 24)
            require(offset >= 0 && offset + dataSize <= ico.size) {
                "ICO frame $i offset=$offset size=$dataSize exceeds file ${ico.size}"
            }
            val png = ico.copyOfRange(offset, offset + dataSize)
            val (pngW, pngH) = pngSize(png)
            IcoFrame(size = size, pngWidth = pngW, pngHeight = pngH)
        }
    }
}

@Composable
private fun SolidRedIcon() {
    Box(Modifier.fillMaxSize().background(Color.Red))
}

/** Minimal valid 1×1 RGBA PNG (same bytes as the Linux SNI concurrency fixture). */
private val MINIMAL_PNG =
    byteArrayOf(
        0x89.toByte(),
        0x50,
        0x4E,
        0x47,
        0x0D,
        0x0A,
        0x1A,
        0x0A,
        0x00,
        0x00,
        0x00,
        0x0D,
        0x49,
        0x48,
        0x44,
        0x52,
        0x00,
        0x00,
        0x00,
        0x01,
        0x00,
        0x00,
        0x00,
        0x01,
        0x08,
        0x06,
        0x00,
        0x00,
        0x00,
        0x1F,
        0x15.toByte(),
        0xC4.toByte(),
        0x89.toByte(),
        0x00,
        0x00,
        0x00,
        0x0D,
        0x49,
        0x44,
        0x41,
        0x54,
        0x78,
        0x9C.toByte(),
        0x62,
        0xF8.toByte(),
        0xCF.toByte(),
        0xC0.toByte(),
        0x00,
        0x00,
        0x00,
        0x03,
        0x00,
        0x01,
        0x73,
        0xF8.toByte(),
        0x6C,
        0xC4.toByte(),
        0x00,
        0x00,
        0x00,
        0x00,
        0x49,
        0x45,
        0x4E,
        0x44,
        0xAE.toByte(),
        0x42,
        0x60,
        0x82.toByte(),
    )
