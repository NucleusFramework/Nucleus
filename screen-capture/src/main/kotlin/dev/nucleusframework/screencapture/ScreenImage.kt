package dev.nucleusframework.screencapture

import dev.nucleusframework.screencapture.internal.PngEncoder
import java.io.OutputStream

/**
 * A captured image, in physical pixels.
 *
 * Pixels are opaque, row-major, packed as `0xAARRGGBB` — the layout of Skia's
 * `BGRA_8888` on little-endian machines, and of `java.awt.image.BufferedImage.TYPE_INT_ARGB`.
 *
 * @property width width in pixels.
 * @property height height in pixels.
 * @property scaleFactor physical pixels per logical unit of the captured source (see
 *   [CaptureDisplay.scaleFactor]); `1` when the platform does not report one.
 */
public class ScreenImage internal constructor(
    public val width: Int,
    public val height: Int,
    private val argb: IntArray,
    public val scaleFactor: Float,
) {
    init {
        require(width > 0 && height > 0 && argb.size == width * height) {
            "Pixel buffer does not match ${width}x$height: ${argb.size}"
        }
    }

    /** The pixel at ([x], [y]), as `0xAARRGGBB`. */
    public fun pixelAt(
        x: Int,
        y: Int,
    ): Int {
        require(x in 0 until width && y in 0 until height) { "($x, $y) outside ${width}x$height" }
        return argb[y * width + x]
    }

    /** A copy of every pixel, row-major, `0xAARRGGBB`. */
    public fun toArgbArray(): IntArray = argb.copyOf()

    /**
     * The pixels as `B, G, R, A` bytes, row-major, `width * 4` bytes per row — ready for
     * `org.jetbrains.skia.Image.makeRaster(ImageInfo(width, height, ColorType.BGRA_8888,
     * ColorAlphaType.OPAQUE), bytes, width * 4)`.
     */
    @Suppress("MagicNumber") // byte lanes of 0xAARRGGBB
    public fun toBgraBytes(): ByteArray {
        val out = ByteArray(argb.size * BYTES_PER_PIXEL)
        var o = 0
        for (p in argb) {
            out[o] = p.toByte()
            out[o + 1] = (p ushr 8).toByte()
            out[o + 2] = (p ushr 16).toByte()
            out[o + 3] = (p ushr 24).toByte()
            o += BYTES_PER_PIXEL
        }
        return out
    }

    /** Encodes the image as an RGB PNG. */
    public fun toPng(): ByteArray = PngEncoder.encode(width, height, argb)

    /** Writes the image to [out] as an RGB PNG; [out] is left open. */
    public fun writePng(out: OutputStream) {
        out.write(toPng())
    }

    /** A new image holding the [region] of this one. */
    public fun crop(region: CaptureRegion): ScreenImage {
        require(region.x >= 0 && region.y >= 0 && region.right <= width && region.bottom <= height) {
            "$region outside ${width}x$height"
        }
        val out = IntArray(region.width * region.height)
        for (row in 0 until region.height) {
            System.arraycopy(argb, (region.y + row) * width + region.x, out, row * region.width, region.width)
        }
        return ScreenImage(region.width, region.height, out, scaleFactor)
    }

    override fun toString(): String = "ScreenImage(${width}x$height, scale=$scaleFactor)"

    private companion object {
        const val BYTES_PER_PIXEL = 4
    }
}
