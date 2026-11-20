package dev.nucleusframework.screencapture

import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.util.zip.CRC32
import java.util.zip.InflaterInputStream
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ScreenImageTest {
    private fun image(
        width: Int,
        height: Int,
        seed: Int = 1,
    ): ScreenImage {
        val random = Random(seed)
        return ScreenImage(width, height, IntArray(width * height) { random.nextInt() or 0xFF000000.toInt() }, 1f)
    }

    @Test
    fun `png round-trips every pixel`() {
        for ((w, h) in listOf(1 to 1, 7 to 3, 257 to 129, 1 to 300, 300 to 1)) {
            val source = image(w, h, seed = w * 31 + h)
            val decoded = decodePng(source.toPng())
            assertEquals(w, decoded.first)
            assertEquals(h, decoded.second)
            assertContentEquals(source.toArgbArray(), decoded.third, "${w}x$h")
        }
    }

    @Test
    fun `bgra bytes follow the argb ints`() {
        val source = ScreenImage(2, 1, intArrayOf(0xFF112233.toInt(), 0xFFAABBCC.toInt()), 1f)
        assertContentEquals(
            byteArrayOf(0x33, 0x22, 0x11, 0xFF.toByte(), 0xCC.toByte(), 0xBB.toByte(), 0xAA.toByte(), 0xFF.toByte()),
            source.toBgraBytes(),
        )
    }

    @Test
    fun `crop copies the region`() {
        val source = image(10, 8)
        val region = CaptureRegion(3, 2, 4, 5)
        val cropped = source.crop(region)
        assertEquals(4, cropped.width)
        assertEquals(5, cropped.height)
        for (y in 0 until 5) {
            for (x in 0 until 4) assertEquals(source.pixelAt(x + 3, y + 2), cropped.pixelAt(x, y))
        }
        assertFailsWith<IllegalArgumentException> { source.crop(CaptureRegion(8, 0, 3, 1)) }
        assertFailsWith<IllegalArgumentException> { source.crop(CaptureRegion(-1, 0, 3, 1)) }
    }

    @Test
    fun `invalid shapes are rejected`() {
        assertFailsWith<IllegalArgumentException> { CaptureRegion(0, 0, 0, 1) }
        assertFailsWith<IllegalArgumentException> { CaptureRegion(0, 0, 1, -1) }
        assertFailsWith<IllegalArgumentException> { ScreenImage(2, 2, IntArray(3), 1f) }
        assertFailsWith<IllegalArgumentException> { image(2, 2).pixelAt(2, 0) }
    }
}

/** A strict decoder for what [ScreenImage.toPng] writes: 8-bit RGB, any filter, chunk CRCs verified. */
internal fun decodePng(bytes: ByteArray): Triple<Int, Int, IntArray> {
    val input = DataInputStream(ByteArrayInputStream(bytes))
    val signature = ByteArray(8).also { input.readFully(it) }
    check(signature.contentEquals(byteArrayOf(0x89.toByte(), 80, 78, 71, 13, 10, 26, 10))) { "bad signature" }
    var width = 0
    var height = 0
    val idat = java.io.ByteArrayOutputStream()
    while (true) {
        val length = input.readInt()
        val type = ByteArray(4).also { input.readFully(it) }
        val data = ByteArray(length).also { input.readFully(it) }
        val crc = input.readInt()
        check(
            CRC32()
                .apply {
                    update(type)
                    update(data)
                }.value
                .toInt() == crc,
        ) { "bad CRC" }
        when (String(type, Charsets.US_ASCII)) {
            "IHDR" -> {
                val header = DataInputStream(ByteArrayInputStream(data))
                width = header.readInt()
                height = header.readInt()
                check(header.readByte().toInt() == 8 && header.readByte().toInt() == 2) { "not 8-bit RGB" }
            }
            "IDAT" -> idat.write(data)
            "IEND" -> break
        }
    }
    val raw = InflaterInputStream(ByteArrayInputStream(idat.toByteArray())).readBytes()
    val stride = width * 3
    check(raw.size == height * (stride + 1)) { "bad IDAT size ${raw.size}" }
    val out = IntArray(width * height)
    var previous = IntArray(stride)
    for (y in 0 until height) {
        val filter = raw[y * (stride + 1)].toInt()
        val line = IntArray(stride)
        for (i in 0 until stride) {
            val value = raw[y * (stride + 1) + 1 + i].toInt() and 0xFF
            val left = if (i >= 3) line[i - 3] else 0
            val up = previous[i]
            line[i] =
                when (filter) {
                    0 -> value
                    1 -> value + left
                    2 -> value + up
                    else -> error("unexpected filter $filter")
                } and 0xFF
        }
        for (x in 0 until width) {
            out[y * width + x] = (0xFF shl 24) or (line[x * 3] shl 16) or (line[x * 3 + 1] shl 8) or line[x * 3 + 2]
        }
        previous = line
    }
    return Triple(width, height, out)
}
