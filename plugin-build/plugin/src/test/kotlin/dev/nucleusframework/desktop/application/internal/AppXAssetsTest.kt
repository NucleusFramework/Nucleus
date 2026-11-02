package dev.nucleusframework.desktop.application.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.imageio.ImageIO

class AppXAssetsTest {
    @get:Rule
    val tmpDir = TemporaryFolder()

    // ---- appXAssetFiles ----

    @Test
    fun `every logo has the five scales, sized from its scale-100 size`() {
        val wide = appXAssetFiles(AppXLogo.Wide310x150Logo)

        assertEquals(
            listOf(
                AppXAssetFile("Wide310x150Logo.scale-100.png", 310, 150),
                AppXAssetFile("Wide310x150Logo.scale-125.png", 388, 188),
                AppXAssetFile("Wide310x150Logo.scale-150.png", 465, 225),
                AppXAssetFile("Wide310x150Logo.scale-200.png", 620, 300),
                AppXAssetFile("Wide310x150Logo.scale-400.png", 1240, 600),
            ),
            wide,
        )
    }

    @Test
    fun `the app icon also has its target sizes, plated and unplated`() {
        val names = appXAssetFiles(AppXLogo.Square44x44Logo).map { it.fileName }

        assertEquals(5 + 5 * 3, names.size)
        assertTrue("Square44x44Logo.scale-200.png" in names)
        assertTrue("Square44x44Logo.targetsize-16.png" in names)
        assertTrue("Square44x44Logo.targetsize-24_altform-unplated.png" in names)
        assertTrue("Square44x44Logo.targetsize-256_altform-lightunplated.png" in names)
    }

    @Test
    fun `only qualified names, so electron-builder builds resources pri`() {
        val all = AppXLogo.entries.flatMap(::appXAssetFiles).map { it.fileName }

        assertTrue(all.all { ".scale-" in it || ".targetsize-" in it })
    }

    // ---- writeAppXAssets ----

    @Test
    fun `writes every file at its size, the source fitted on a transparent canvas`() {
        val dir = tmpDir.newFolder("appx")

        val enlarged = writeAppXAssets(AppXLogo.Wide310x150Logo, opaqueSquare(1024), dir)

        assertTrue(enlarged.isEmpty())
        for (file in appXAssetFiles(AppXLogo.Wide310x150Logo)) {
            val image = ImageIO.read(dir.resolve(file.fileName))
            assertEquals(file.width, image.width)
            assertEquals(file.height, image.height)
        }
        // The square source fills the height of the wide tile, centered: its corners stay transparent
        val wide = ImageIO.read(dir.resolve("Wide310x150Logo.scale-100.png"))
        assertEquals(0, wide.getRGB(0, 0) ushr 24)
        assertEquals(0xFF, wide.getRGB(155, 75) ushr 24)
    }

    @Test
    fun `reports the files a small source is enlarged for`() {
        val enlarged = writeAppXAssets(AppXLogo.StoreLogo, opaqueSquare(64), tmpDir.newFolder("appx"))

        assertEquals(
            listOf("StoreLogo.scale-150.png", "StoreLogo.scale-200.png", "StoreLogo.scale-400.png"),
            enlarged.map { it.fileName },
        )
    }

    // ---- readIco ----

    @Test
    fun `reads the largest entry of an ico, a png one`() {
        val ico = ico(bmpEntry(16, 32), pngEntry(256), pngEntry(48))

        val image = readIco(ico)

        assertNotNull(image)
        assertEquals(256, image!!.width)
    }

    @Test
    fun `reads a 32 bit bitmap entry with its alpha`() {
        val image = readIco(ico(bmpEntry(32, 32)))

        assertNotNull(image)
        assertEquals(32, image!!.width)
        assertEquals(32, image.height)
        assertEquals(RED, image.getRGB(0, 0))
    }

    @Test
    fun `reads a 24 bit bitmap entry, transparent where its mask says`() {
        val image = readIco(ico(bmpEntry(16, 24, maskedFirstPixel = true)))

        assertNotNull(image)
        assertEquals(0, image!!.getRGB(0, 0) ushr 24)
        assertEquals(RED, image.getRGB(1, 0))
    }

    @Test
    fun `an ico without a readable entry or a non ico gives null`() {
        assertNull(readIco(ico()))
        assertNull(readIco(byteArrayOf(1, 2, 3)))
    }

    @Test
    fun `readIconImage reads an ico file and a png file`() {
        val icoFile = tmpDir.newFile("app.ico").apply { writeBytes(ico(pngEntry(128))) }
        val pngFile = tmpDir.newFile("app.png").apply { ImageIO.write(opaqueSquare(64), "png", this) }

        assertEquals(128, readIconImage(icoFile)?.width)
        assertEquals(64, readIconImage(pngFile)?.width)
        assertNull(readIconImage(tmpDir.root.resolve("missing.png")))
    }

    // ---- fixtures ----

    private fun opaqueSquare(size: Int) =
        BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB).apply {
            for (x in 0 until size) for (y in 0 until size) setRGB(x, y, RED)
        }

    private fun pngEntry(size: Int): ByteArray =
        ByteArrayOutputStream().also { ImageIO.write(opaqueSquare(size), "png", it) }.toByteArray()

    /** A BITMAPINFOHEADER entry of red pixels, its height doubled for the AND mask that follows. */
    private fun bmpEntry(
        size: Int,
        bitCount: Int,
        maskedFirstPixel: Boolean = false,
    ): ByteArray {
        val bytesPerPixel = bitCount / 8
        val rowSize = (size * bytesPerPixel + 3) / 4 * 4
        val maskRowSize = (size + 31) / 32 * 4
        val buffer = ByteBuffer.allocate(40 + rowSize * size + maskRowSize * size).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(40).putInt(size).putInt(size * 2).putShort(1).putShort(bitCount.toShort())
        buffer.position(40)
        repeat(size) {
            val row = ByteArray(rowSize)
            for (x in 0 until size) {
                row[x * bytesPerPixel + 2] = 0xFF.toByte()
                if (bitCount == 32) row[x * bytesPerPixel + 3] = 0xFF.toByte()
            }
            buffer.put(row)
        }
        // The mask's rows are bottom-up too: the image's first row is the last one
        repeat(size) { row ->
            val mask = ByteArray(maskRowSize)
            if (maskedFirstPixel && row == size - 1) mask[0] = 0x80.toByte()
            buffer.put(mask)
        }
        return buffer.array()
    }

    private fun ico(vararg entries: ByteArray): ByteArray {
        val header = 6 + 16 * entries.size
        val buffer = ByteBuffer.allocate(header + entries.sumOf { it.size }).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putShort(0).putShort(1).putShort(entries.size.toShort())
        var offset = header
        for (entry in entries) {
            buffer.put(0).put(0).put(0).put(0).putShort(1).putShort(32).putInt(entry.size).putInt(offset)
            offset += entry.size
        }
        entries.forEach { buffer.put(it) }
        return buffer.array()
    }

    private companion object {
        const val RED = 0xFFFF0000.toInt()
    }
}
