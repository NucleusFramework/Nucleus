package dev.nucleusframework.tray.utils

import androidx.compose.runtime.Composable
import androidx.compose.ui.ImageComposeScene
import dev.nucleusframework.tray.InternalNucleusTrayApi
import kotlinx.coroutines.Dispatchers
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.FilterMipmap
import org.jetbrains.skia.FilterMode
import org.jetbrains.skia.Image
import org.jetbrains.skia.MipmapMode
import java.util.zip.CRC32

/**
 * Utility functions for rendering Composable icons to image files for use in system tray.
 */
@InternalNucleusTrayApi
public object ComposableIconUtils {
    /**
     * Renders a Composable to a PNG file and returns the path to the file.
     *
     * @param iconRenderProperties Properties for rendering the icon
     * @param content The Composable content to render
     * @return Path to the generated PNG file
     * @throws Exception if rendering fails completely
     */
    public fun renderComposableToPngFile(
        iconRenderProperties: IconRenderProperties,
        content: @Composable () -> Unit,
    ): String = IconFileCache.store(renderComposableToPngBytes(iconRenderProperties, content), "png")

    /**
     * Renders a Composable to a PNG image and returns the result as a byte array.
     * This function creates an [ImageComposeScene] based on the provided [IconRenderProperties],
     * renders the Composable content, and encodes the output into PNG format.
     * If [IconRenderProperties.requiresScaling] is true, the rendered content is scaled
     * to [IconRenderProperties.targetWidth]/[IconRenderProperties.targetHeight] before encoding.
     * Otherwise the scene-resolution master is encoded as-is so native backends can downsample.
     *
     * @param iconRenderProperties Properties for rendering the icon
     * @param content The Composable content to render
     * @return A byte array containing the rendered PNG image data.
     * @throws Exception if rendering fails
     */
    public fun renderComposableToPngBytes(
        iconRenderProperties: IconRenderProperties,
        content: @Composable () -> Unit,
    ): ByteArray =
        withRenderedIcon(iconRenderProperties, content) { image ->
            image.encodeToPngBytes()
        }

    private fun Image.encodeToPngBytes(): ByteArray {
        val data = encodeToData(EncodedImageFormat.PNG) ?: throw IllegalStateException("Failed to encode image to PNG")
        return data.use { it.bytes }
    }

    /**
     * Renders a Composable to an ICO file and returns the path to the file.
     *
     * @param iconRenderProperties Properties for rendering the icon
     * @param content The Composable content to render
     * @return Path to the generated ICO file
     * @throws Exception if rendering fails
     */
    public fun renderComposableToIcoFile(
        iconRenderProperties: IconRenderProperties,
        content: @Composable (() -> Unit),
    ): String = IconFileCache.store(renderComposableToIcoBytes(iconRenderProperties, content), "ico")

    /**
     * Renders a Composable to ICO format bytes.
     *
     * Encodes a multi-frame ICO (16/20/24/32/40/48/64, clipped to the master size)
     * so the Windows shell can pick an exact match at any DPI. Frames larger than
     * the master are omitted — never upscaled.
     *
     * @param iconRenderProperties Properties for rendering the icon
     * @param content The Composable content to render
     * @return Byte array containing the ICO data
     * @throws Exception if rendering fails
     */
    public fun renderComposableToIcoBytes(
        iconRenderProperties: IconRenderProperties,
        content: @Composable () -> Unit,
    ): ByteArray =
        withRenderedIcon(iconRenderProperties, content) { master ->
            val frames =
                icoFrameSizesFor(master.width, master.height).map { size ->
                    size to master.encodeScaledPng(size, size)
                }
            packPngFramesAsIco(frames)
        }

    /**
     * Calculates a hash value for the rendered composable content.
     * This can be used to detect changes in the composable content without requiring an explicit key.
     *
     * @param iconRenderProperties Properties for rendering the icon
     * @param content The Composable content to render
     * @return A hash value representing the current state of the composable content
     */
    @Composable
    public fun calculateContentHash(
        iconRenderProperties: IconRenderProperties,
        content: @Composable () -> Unit,
    ): Long =
        try {
            // Render the composable to PNG bytes
            val pngBytes = renderComposableToPngBytes(iconRenderProperties, content)

            // Calculate CRC32 hash of the PNG bytes
            val crc = CRC32()
            crc.update(pngBytes)
            crc.value
        } catch (e: Exception) {
            errorln { "[ComposableIconUtils] Failed to calculate content hash: ${e.message}" }
            // Return a time-based hash as fallback
            System.currentTimeMillis()
        }

    private fun <T> withRenderedIcon(
        iconRenderProperties: IconRenderProperties,
        content: @Composable () -> Unit,
        block: (Image) -> T,
    ): T {
        var scene: ImageComposeScene? = null
        var renderedIcon: Image? = null
        var scaledBitmap: Bitmap? = null
        var scaledImage: Image? = null
        try {
            try {
                scene =
                    ImageComposeScene(
                        width = iconRenderProperties.sceneWidth,
                        height = iconRenderProperties.sceneHeight,
                        density = iconRenderProperties.sceneDensity,
                        coroutineContext = Dispatchers.Unconfined,
                    ) {
                        content()
                    }
                renderedIcon = scene.render()
            } catch (e: Exception) {
                val errorMessage = e.message ?: "Unknown error"
                errorln { "[ComposableIconUtils] Failed to render scene: $errorMessage" }
                if (errorMessage.contains("DirectX12", ignoreCase = true) ||
                    errorMessage.contains("Failed to choose DirectX12 adapter", ignoreCase = true)
                ) {
                    errorln { "[ComposableIconUtils] DirectX12 not available on this system. Scene rendering failed." }
                }
                throw e
            }

            val image =
                if (iconRenderProperties.requiresScaling) {
                    scaledBitmap =
                        Bitmap().apply {
                            allocN32Pixels(iconRenderProperties.targetWidth, iconRenderProperties.targetHeight)
                        }
                    renderedIcon.scalePixels(
                        scaledBitmap.peekPixels()!!,
                        FilterMipmap(FilterMode.LINEAR, MipmapMode.LINEAR),
                        true,
                    )
                    scaledImage = Image.makeFromBitmap(scaledBitmap)
                    scaledImage
                } else {
                    renderedIcon
                }

            return block(image)
        } finally {
            try {
                scaledImage?.close()
                scaledBitmap?.close()
                renderedIcon?.close()
                scene?.close()
            } catch (e: Exception) {
                debugln { "[ComposableIconUtils] Error during cleanup: ${e.message}" }
            }
        }
    }

    private fun Image.encodeScaledPng(
        width: Int,
        height: Int,
    ): ByteArray {
        if (this.width == width && this.height == height) {
            return encodeToPngBytes()
        }
        var bitmap: Bitmap? = null
        var scaled: Image? = null
        try {
            bitmap = Bitmap().apply { allocN32Pixels(width, height) }
            scalePixels(
                bitmap.peekPixels()!!,
                FilterMipmap(FilterMode.LINEAR, MipmapMode.LINEAR),
                true,
            )
            scaled = Image.makeFromBitmap(bitmap)
            return scaled.encodeToPngBytes()
        } finally {
            scaled?.close()
            bitmap?.close()
        }
    }
}

/** Standard Windows small-icon sizes covering 100%–400% DPI. */
internal val WINDOWS_ICO_FRAME_SIZES = intArrayOf(16, 20, 24, 32, 40, 48, 64)

/**
 * ICO frame sizes to emit for a master of [masterWidth]×[masterHeight].
 * Never larger than the master — the shell upscaling a missing size is better
 * than us interpolating past the source.
 */
internal fun icoFrameSizesFor(
    masterWidth: Int,
    masterHeight: Int,
): List<Int> {
    val max = minOf(masterWidth, masterHeight)
    val sizes = WINDOWS_ICO_FRAME_SIZES.filter { it <= max }
    return sizes.ifEmpty { listOf(max.coerceIn(1, 256)) }
}

/** Packs PNG blobs into a multi-frame ICO container (Vista+ PNG-in-ICO). */
internal fun packPngFramesAsIco(frames: List<Pair<Int, ByteArray>>): ByteArray {
    require(frames.isNotEmpty()) { "ICO must contain at least one frame" }
    val headerSize = 6
    val entrySize = 16
    val dataStart = headerSize + entrySize * frames.size
    val total = dataStart + frames.sumOf { it.second.size }
    val ico = ByteArray(total)
    ico[2] = 1
    ico[4] = frames.size.toByte()
    ico[5] = (frames.size shr 8).toByte()
    var offset = dataStart
    frames.forEachIndexed { index, (size, png) ->
        val entry = headerSize + index * entrySize
        val dim = if (size >= 256) 0 else size
        ico[entry] = dim.toByte()
        ico[entry + 1] = dim.toByte()
        ico[entry + 4] = 1
        ico[entry + 6] = 32
        writeIntLe(ico, entry + 8, png.size)
        writeIntLe(ico, entry + 12, offset)
        System.arraycopy(png, 0, ico, offset, png.size)
        offset += png.size
    }
    return ico
}

private fun writeIntLe(
    dest: ByteArray,
    index: Int,
    value: Int,
) {
    dest[index] = (value and 0xFF).toByte()
    dest[index + 1] = ((value shr 8) and 0xFF).toByte()
    dest[index + 2] = ((value shr 16) and 0xFF).toByte()
    dest[index + 3] = ((value shr 24) and 0xFF).toByte()
}
