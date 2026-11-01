// The AppX logo sizes are the ones the manifest schema fixes, the offsets those of the ICO and BMP formats
@file:Suppress("MagicNumber")

package dev.nucleusframework.desktop.application.internal

import net.coobird.thumbnailator.Thumbnails
import net.coobird.thumbnailator.filters.Canvas
import net.coobird.thumbnailator.geometry.Positions
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.imageio.ImageIO
import kotlin.math.roundToInt

/** A logo the AppX manifest references, at its scale-100 size. */
internal enum class AppXLogo(
    val baseName: String,
    val width: Int,
    val height: Int,
) {
    StoreLogo("StoreLogo", 50, 50),
    Square44x44Logo("Square44x44Logo", 44, 44),
    Square150x150Logo("Square150x150Logo", 150, 150),
    Wide310x150Logo("Wide310x150Logo", 310, 150),
}

/** One generated file: its name in the package's `assets` folder and its size in pixels. */
internal data class AppXAssetFile(
    val fileName: String,
    val width: Int,
    val height: Int,
)

/** The scales Windows picks from by display scaling, as Visual Studio generates them. */
private val APPX_SCALES = listOf(100, 125, 150, 200, 400)

/** The sizes the taskbar, the Start list and Explorer ask the app's icon (Square44x44Logo) for. */
private val APPX_TARGET_SIZES = listOf(16, 24, 32, 48, 256)

/**
 * The plates of the target-size icons: on a plate of the tile color, without one (dark taskbar
 * and Start), and without one on a light theme.
 */
private val APPX_TARGET_SIZE_FORMS = listOf("", "_altform-unplated", "_altform-lightunplated")

private const val PERCENT = 100.0

/**
 * Every file of [logo]: its scale variants and, for the app icon, its target-size ones. Only
 * qualified names: with them electron-builder indexes the assets in `resources.pri` and Windows
 * picks the one for the display's scaling instead of stretching a single image.
 */
internal fun appXAssetFiles(logo: AppXLogo): List<AppXAssetFile> {
    val scaled =
        APPX_SCALES.map { scale ->
            AppXAssetFile(
                fileName = "${logo.baseName}.scale-$scale.png",
                width = (logo.width * scale / PERCENT).roundToInt(),
                height = (logo.height * scale / PERCENT).roundToInt(),
            )
        }
    if (logo != AppXLogo.Square44x44Logo) return scaled
    val targetSized =
        APPX_TARGET_SIZES.flatMap { size ->
            APPX_TARGET_SIZE_FORMS.map { form ->
                AppXAssetFile("${logo.baseName}.targetsize-$size$form.png", size, size)
            }
        }
    return scaled + targetSized
}

/**
 * Writes every file of [logo] into [assetsDir], [source] resized to fit and centered on a
 * transparent canvas of the file's size. Returns the files [source] had to be enlarged for.
 */
internal fun writeAppXAssets(
    logo: AppXLogo,
    source: BufferedImage,
    assetsDir: File,
): List<AppXAssetFile> {
    assetsDir.mkdirs()
    val files = appXAssetFiles(logo)
    for (file in files) {
        ImageIO.write(fitToCanvas(source, file.width, file.height), "png", assetsDir.resolve(file.fileName))
    }
    return files.filter { minOf(it.width.toDouble() / source.width, it.height.toDouble() / source.height) > 1.0 }
}

private fun fitToCanvas(
    source: BufferedImage,
    width: Int,
    height: Int,
): BufferedImage =
    Thumbnails
        .of(source)
        .size(width, height)
        .keepAspectRatio(true)
        .addFilter(Canvas(width, height, Positions.CENTER, true, Color(0, 0, 0, 0)))
        .imageType(BufferedImage.TYPE_INT_ARGB)
        .asBufferedImage()

/** Reads [file] as an image, a Windows `.ico` included (its largest entry), or null. */
internal fun readIconImage(file: File?): BufferedImage? {
    if (file == null || !file.isFile) return null
    return if (file.extension.equals("ico", ignoreCase = true)) readIco(file.readBytes()) else ImageIO.read(file)
}

private const val ICO_HEADER_SIZE = 6
private const val ICO_ENTRY_SIZE = 16
private const val ICO_TYPE_ICON = 1
private const val ICO_MAX_DIMENSION = 256
private const val BMP_INFO_HEADER_SIZE = 40
private const val BITS_32 = 32
private const val BITS_24 = 24
private val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte())

/**
 * The largest image of an `.ico` ([bytes]), which `ImageIO` cannot read: its PNG entries (the
 * 256 px one of any modern icon) and its 32 or 24 bit bitmaps. Null if none of them decodes.
 */
internal fun readIco(bytes: ByteArray): BufferedImage? {
    if (bytes.size < ICO_HEADER_SIZE) return null
    val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    if (buffer.getShort(2).toInt() != ICO_TYPE_ICON) return null
    val count = buffer.getShort(4).toInt() and 0xFFFF
    return (0 until count)
        .mapNotNull { index ->
            val entry = ICO_HEADER_SIZE + index * ICO_ENTRY_SIZE
            if (entry + ICO_ENTRY_SIZE > bytes.size) return@mapNotNull null
            val size = buffer.getInt(entry + 8)
            val offset = buffer.getInt(entry + 12)
            if (size <= 0 || offset < 0 || offset.toLong() + size > bytes.size) return@mapNotNull null
            decodeIcoEntry(bytes.copyOfRange(offset, offset + size))
        }.maxByOrNull { it.width * it.height }
}

private fun decodeIcoEntry(data: ByteArray): BufferedImage? =
    if (data.size >= PNG_SIGNATURE.size && data.copyOf(PNG_SIGNATURE.size).contentEquals(PNG_SIGNATURE)) {
        runCatching { ImageIO.read(ByteArrayInputStream(data)) }.getOrNull()
    } else {
        decodeIcoBitmap(data)
    }

/**
 * An `.ico` bitmap entry: a BITMAPINFOHEADER whose height counts the color rows and the AND mask,
 * the rows bottom-up, then the 1 bit AND mask (the transparency of 24 bit bitmaps).
 */
@Suppress("ReturnCount")
private fun decodeIcoBitmap(data: ByteArray): BufferedImage? {
    if (data.size < BMP_INFO_HEADER_SIZE) return null
    val buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
    val headerSize = buffer.getInt(0)
    val width = buffer.getInt(4)
    val height = buffer.getInt(8) / 2
    val bitCount = buffer.getShort(14).toInt()
    if (headerSize < BMP_INFO_HEADER_SIZE) return null
    if (width !in 1..ICO_MAX_DIMENSION || height !in 1..ICO_MAX_DIMENSION) return null
    if (bitCount != BITS_32 && bitCount != BITS_24) return null

    val bytesPerPixel = bitCount / 8
    val rowSize = (width * bytesPerPixel + 3) / 4 * 4
    val maskRowSize = (width + 31) / 32 * 4
    val maskStart = headerSize + rowSize * height
    if (maskStart > data.size) return null
    val hasMask = maskStart + maskRowSize * height <= data.size

    val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
    for (y in 0 until height) {
        val row = headerSize + (height - 1 - y) * rowSize
        val maskRow = maskStart + (height - 1 - y) * maskRowSize
        for (x in 0 until width) {
            val pixel = row + x * bytesPerPixel
            val blue = data[pixel].toInt() and 0xFF
            val green = data[pixel + 1].toInt() and 0xFF
            val red = data[pixel + 2].toInt() and 0xFF
            val alpha =
                when {
                    bitCount == BITS_32 -> data[pixel + 3].toInt() and 0xFF
                    hasMask && (data[maskRow + x / 8].toInt() shr (7 - x % 8)) and 1 == 1 -> 0
                    else -> 0xFF
                }
            image.setRGB(x, y, (alpha shl 24) or (red shl 16) or (green shl 8) or blue)
        }
    }
    return image
}
