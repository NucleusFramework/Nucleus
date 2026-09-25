package dev.nucleusframework.screencapture.internal

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream

/** Minimal PNG writer (8-bit RGB, filter `Sub`) — no ImageIO, so no AWT and no native-image metadata. */
@Suppress("MagicNumber") // PNG signature bytes and RGB byte lanes
internal object PngEncoder {
    private val SIGNATURE =
        byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10)
    private const val COLOR_TYPE_RGB = 2
    private const val BIT_DEPTH = 8
    private const val FILTER_SUB = 1
    private const val CHANNELS = 3

    fun encode(
        width: Int,
        height: Int,
        argb: IntArray,
    ): ByteArray {
        val out = ByteArrayOutputStream(argb.size + 1024)
        out.write(SIGNATURE)
        val header = ByteArrayOutputStream()
        DataOutputStream(header).apply {
            writeInt(width)
            writeInt(height)
            writeByte(BIT_DEPTH)
            writeByte(COLOR_TYPE_RGB)
            writeByte(0) // compression
            writeByte(0) // filter method
            writeByte(0) // no interlace
        }
        writeChunk(out, "IHDR", header.toByteArray())
        writeChunk(out, "IDAT", compress(width, height, argb))
        writeChunk(out, "IEND", ByteArray(0))
        return out.toByteArray()
    }

    private fun compress(
        width: Int,
        height: Int,
        argb: IntArray,
    ): ByteArray {
        val stride = width * CHANNELS
        val row = ByteArray(1 + stride)
        val compressed = ByteArrayOutputStream(argb.size)
        val deflater = Deflater(Deflater.BEST_SPEED)
        try {
            DeflaterOutputStream(compressed, deflater, 1 shl 16).use { z ->
                for (y in 0 until height) {
                    row[0] = FILTER_SUB.toByte()
                    var prevR = 0
                    var prevG = 0
                    var prevB = 0
                    var o = 1
                    val base = y * width
                    for (x in 0 until width) {
                        val p = argb[base + x]
                        val r = (p ushr 16) and 0xFF
                        val g = (p ushr 8) and 0xFF
                        val b = p and 0xFF
                        row[o] = (r - prevR).toByte()
                        row[o + 1] = (g - prevG).toByte()
                        row[o + 2] = (b - prevB).toByte()
                        prevR = r
                        prevG = g
                        prevB = b
                        o += CHANNELS
                    }
                    z.write(row)
                }
            }
        } finally {
            deflater.end()
        }
        return compressed.toByteArray()
    }

    private fun writeChunk(
        out: ByteArrayOutputStream,
        type: String,
        data: ByteArray,
    ) {
        val typeBytes = type.toByteArray(Charsets.US_ASCII)
        val crc =
            CRC32().apply {
                update(typeBytes)
                update(data)
            }
        DataOutputStream(out).apply {
            writeInt(data.size)
            write(typeBytes)
            write(data)
            writeInt(crc.value.toInt())
        }
    }
}
