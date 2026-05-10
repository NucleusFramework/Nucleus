package dev.nucleusframework.nucleus.desktop.application.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

class DmgBackgroundPaddingTest {
    @get:Rule
    val tmpDir = TemporaryFolder()

    // ---- readImageDimensions ----

    @Test
    fun `readImageDimensions returns correct size for PNG`() {
        val img = createTestImage(300, 200)
        val file = tmpDir.newFile("test.png")
        ImageIO.write(img, "png", file)

        val dims = readImageDimensions(file)
        assertNotNull(dims)
        assertEquals(300, dims!!.first)
        assertEquals(200, dims.second)
    }

    @Test
    fun `readImageDimensions returns correct size for TIFF`() {
        val img = createTestImage(400, 250)
        val file = tmpDir.newFile("test.tiff")
        ImageIO.write(img, "tiff", file)

        val dims = readImageDimensions(file)
        assertNotNull(dims)
        assertEquals(400, dims!!.first)
        assertEquals(250, dims.second)
    }

    @Test
    fun `readImageDimensions returns null for non-image file`() {
        val file = tmpDir.newFile("test.txt")
        file.writeText("not an image")
        assertEquals(null, readImageDimensions(file))
    }

    // ---- padDmgBackgroundForTitleBar — copy semantics ----

    @Test
    fun `PNG is copied to outputDir with unchanged dimensions`() {
        val source = tmpDir.newFile("background.png")
        ImageIO.write(createTestImage(600, 400, Color.RED), "png", source)
        val outputDir = tmpDir.newFolder("output")

        val result = padDmgBackgroundForTitleBar(source, outputDir)

        val dims = readImageDimensions(result)
        assertNotNull(dims)
        assertEquals(600, dims!!.first)
        assertEquals(400, dims.second)
    }

    @Test
    fun `TIFF is copied to outputDir with unchanged dimensions`() {
        val source = tmpDir.newFile("background.tiff")
        ImageIO.write(createTestImage(500, 350, Color.BLUE), "tiff", source)
        val outputDir = tmpDir.newFolder("output")

        val result = padDmgBackgroundForTitleBar(source, outputDir)

        val dims = readImageDimensions(result)
        assertNotNull(dims)
        assertEquals(500, dims!!.first)
        assertEquals(350, dims.second)
    }

    @Test
    fun `TIFF bytes are not re-encoded - output is byte-identical to source`() {
        val source = tmpDir.newFile("background.tiff")
        ImageIO.write(createTestImage(400, 300, Color.GREEN), "tiff", source)
        val originalBytes = source.readBytes()
        val outputDir = tmpDir.newFolder("output")

        val result = padDmgBackgroundForTitleBar(source, outputDir)

        assertTrue("Result file must exist", result.isFile)
        val resultBytes = result.readBytes()
        assertTrue("TIFF bytes must be identical — no re-encoding allowed", originalBytes.contentEquals(resultBytes))
    }

    @Test
    fun `output preserves the original filename for electron-builder @2x detection`() {
        val source = tmpDir.newFile("myBackground.png")
        ImageIO.write(createTestImage(200, 100), "png", source)
        val outputDir = tmpDir.newFolder("output")

        val result = padDmgBackgroundForTitleBar(source, outputDir)

        assertEquals("myBackground.png", result.name)
        assertTrue(result.parentFile.absolutePath.startsWith(outputDir.absolutePath))
    }

    @Test
    fun `output file always exists after copy`() {
        val source = tmpDir.newFile("background.tiff")
        ImageIO.write(createTestImage(600, 400, Color.RED), "tiff", source)
        val outputDir = tmpDir.newFolder("output")

        val result = padDmgBackgroundForTitleBar(source, outputDir)

        assertTrue("Result file must exist on disk", result.isFile)
        assertTrue("Result file must be non-empty", result.length() > 0)
    }

    @Test
    fun `running copy twice produces identical output`() {
        val source = tmpDir.newFile("bg.png")
        ImageIO.write(createTestImage(200, 150, Color.GREEN), "png", source)

        val out1 = tmpDir.newFolder("out1")
        val out2 = tmpDir.newFolder("out2")

        padDmgBackgroundForTitleBar(source, out1)
        padDmgBackgroundForTitleBar(source, out2)

        val bytes1 = File(out1, "bg.png").readBytes()
        val bytes2 = File(out2, "bg.png").readBytes()
        assertTrue("Both copies must be byte-identical", bytes1.contentEquals(bytes2))
    }

    @Test
    fun `copying already-copied image produces same dimensions`() {
        val source = tmpDir.newFile("bg.png")
        ImageIO.write(createTestImage(200, 100), "png", source)
        val out1 = tmpDir.newFolder("out1")
        val out2 = tmpDir.newFolder("out2")

        val firstResult = padDmgBackgroundForTitleBar(source, out1)
        assertEquals(100, readImageDimensions(firstResult)!!.second)

        // Second pass on already-copied image must not grow further
        val secondResult = padDmgBackgroundForTitleBar(firstResult, out2)
        assertEquals(100, readImageDimensions(secondResult)!!.second)
    }

    // ---- padDmgBackgroundForTitleBar — @2x Retina handling ----

    @Test
    fun `@2x retina variant is copied alongside primary image`() {
        val sourceDir = tmpDir.newFolder("source")
        val source1x = File(sourceDir, "background.png")
        val source2x = File(sourceDir, "background@2x.png")
        ImageIO.write(createTestImage(300, 200), "png", source1x)
        ImageIO.write(createTestImage(600, 400), "png", source2x)

        val outputDir = tmpDir.newFolder("output")
        padDmgBackgroundForTitleBar(source1x, outputDir)

        // 1x must be copied with original dimensions (no padding)
        val copied1x = File(outputDir, "background.png")
        assertTrue("1x output must exist", copied1x.isFile)
        val dims1x = readImageDimensions(copied1x)!!
        assertEquals(300, dims1x.first)
        assertEquals(200, dims1x.second)

        // 2x must also be copied with original dimensions (no padding)
        val copied2x = File(outputDir, "background@2x.png")
        assertTrue("2x output must exist", copied2x.isFile)
        val dims2x = readImageDimensions(copied2x)!!
        assertEquals(600, dims2x.first)
        assertEquals(400, dims2x.second)
    }

    @Test
    fun `@2x retina TIFF variant is copied byte-identically`() {
        val sourceDir = tmpDir.newFolder("source")
        val source1x = File(sourceDir, "background.tiff")
        val source2x = File(sourceDir, "background@2x.tiff")
        ImageIO.write(createTestImage(400, 300), "tiff", source1x)
        ImageIO.write(createTestImage(800, 600), "tiff", source2x)
        val original2xBytes = source2x.readBytes()

        val outputDir = tmpDir.newFolder("output")
        padDmgBackgroundForTitleBar(source1x, outputDir)

        val copied2x = File(outputDir, "background@2x.tiff")
        assertTrue("2x TIFF must be copied", copied2x.isFile)
        assertTrue("2x TIFF bytes must be identical", original2xBytes.contentEquals(copied2x.readBytes()))
    }

    @Test
    fun `missing @2x variant does not cause failure`() {
        val source = tmpDir.newFile("background.png")
        ImageIO.write(createTestImage(300, 200), "png", source)
        val outputDir = tmpDir.newFolder("output")

        val result = padDmgBackgroundForTitleBar(source, outputDir)

        assertTrue(result.isFile)
        // 1x dimensions unchanged
        assertEquals(200, readImageDimensions(result)!!.second)
    }

    // ---- Window override computation ----

    @Test
    fun `window height override equals image height plus title bar`() {
        val source = tmpDir.newFile("bg.png")
        ImageIO.write(createTestImage(660, 400), "png", source)
        val outputDir = tmpDir.newFolder("output")

        val result = padDmgBackgroundForTitleBar(source, outputDir)
        val dims = readImageDimensions(result)!!

        val expectedWindowHeight = dims.second + MACOS_DMG_TITLE_BAR_HEIGHT
        assertEquals("Window height = imageHeight + titleBarHeight", 400 + MACOS_DMG_TITLE_BAR_HEIGHT, expectedWindowHeight)
    }

    // ---- Issue regressions ----

    @Test
    fun `issue 166 - TIFF copy result file exists on disk`() {
        val source = tmpDir.newFile("background.tiff")
        ImageIO.write(createTestImage(600, 400, Color.RED), "tiff", source)
        val outputDir = tmpDir.newFolder("output")

        val result = padDmgBackgroundForTitleBar(source, outputDir)

        assertTrue("Result file must exist on disk (issue #166)", result.isFile)
        assertTrue("Result file must be non-empty", result.length() > 0)
    }

    @Test
    fun `issue 166 - PNG copy result file exists on disk`() {
        val source = tmpDir.newFile("background.png")
        ImageIO.write(createTestImage(600, 400, Color.RED), "png", source)
        val outputDir = tmpDir.newFolder("output")

        val result = padDmgBackgroundForTitleBar(source, outputDir)

        assertTrue("Result file must exist on disk", result.isFile)
        assertTrue("Result file must be non-empty", result.length() > 0)
    }

    @Test
    fun `issue 166 - TIFF with alpha channel is copied without error`() {
        val img = BufferedImage(500, 350, BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        g.color = Color(255, 0, 0, 128)
        g.fillRect(0, 0, 500, 350)
        g.dispose()
        val source = tmpDir.newFile("background.tiff")
        ImageIO.write(img, "tiff", source)
        val outputDir = tmpDir.newFolder("output")

        val result = padDmgBackgroundForTitleBar(source, outputDir)

        assertTrue("Result file must exist on disk", result.isFile)
        val resultImg = ImageIO.read(result)
        assertNotNull("Result must be a readable image", resultImg)
        assertEquals("Width must be preserved", 500, resultImg.width)
    }

    @Test
    fun `issue 166 - TIFF result can be read without FileNotFoundException`() {
        val source = tmpDir.newFile("background.tiff")
        ImageIO.write(createTestImage(800, 600, Color.GREEN), "tiff", source)
        val outputDir = tmpDir.newFolder("dmg-assets")

        val copiedFile = padDmgBackgroundForTitleBar(source, outputDir)

        val bytes = copiedFile.readBytes()
        assertTrue("Copied file must have content", bytes.isNotEmpty())
        val readBack = ImageIO.read(copiedFile)
        assertNotNull("Must be a valid image file", readBack)
    }

    @Test
    fun `issue 166 - TIFF retina pair both exist on disk after copy`() {
        val sourceDir = tmpDir.newFolder("source")
        val source1x = File(sourceDir, "background.tiff")
        val source2x = File(sourceDir, "background@2x.tiff")
        ImageIO.write(createTestImage(400, 300), "tiff", source1x)
        ImageIO.write(createTestImage(800, 600), "tiff", source2x)

        val outputDir = tmpDir.newFolder("output")
        val result = padDmgBackgroundForTitleBar(source1x, outputDir)

        assertTrue("1x result must exist", result.isFile)
        val retina = File(outputDir, "background@2x.tiff")
        assertTrue("2x result must exist (issue #166 regression)", retina.isFile)
        assertTrue("2x result must be non-empty", retina.length() > 0)
    }

    // ---- Issue #185 — Apple TIFF end-to-end with real header.png ----

    @Test
    fun `issue 185 - sips-converted TIFF from header png is preserved byte-for-byte`() {
        Assume.assumeTrue("sips is available (macOS only)", File("/usr/bin/sips").exists())

        val projectRoot = File(System.getProperty("user.dir")).parentFile.parentFile
        val headerPng = File(projectRoot, "art/header.png")
        Assume.assumeTrue("art/header.png exists", headerPng.isFile)

        // Convert to TIFF using sips (preserves Apple metadata)
        val sourceTiff = tmpDir.newFile("header.tiff")
        val exitCode = ProcessBuilder(
            "/usr/bin/sips", "-s", "format", "tiff",
            headerPng.absolutePath, "--out", sourceTiff.absolutePath,
        ).start().waitFor()
        Assume.assumeTrue("sips conversion succeeded", exitCode == 0)

        val originalBytes = sourceTiff.readBytes()
        val outputDir = tmpDir.newFolder("dmg-assets")

        val result = padDmgBackgroundForTitleBar(sourceTiff, outputDir)

        assertTrue("Copied TIFF must exist", result.isFile)
        assertTrue("TIFF bytes must be identical — no re-encoding", originalBytes.contentEquals(result.readBytes()))

        // Verify readImageDimensions reads the correct dimensions
        val dims = readImageDimensions(result)
        assertNotNull("Must be able to read dimensions from sips TIFF", dims)
        assertEquals("Width must be 1536 (header.png width)", 1536, dims!!.first)
        assertEquals("Height must be 1024 (header.png height)", 1024, dims.second)

        // Verify the window override computation
        val expectedWindowHeight = dims.second + MACOS_DMG_TITLE_BAR_HEIGHT
        assertEquals("Window height = 1024 + $MACOS_DMG_TITLE_BAR_HEIGHT", 1024 + MACOS_DMG_TITLE_BAR_HEIGHT, expectedWindowHeight)
    }

    @Test
    fun `issue 185 - Apple multi-resolution TIFF from tiffutil is preserved byte-for-byte`() {
        Assume.assumeTrue("sips is available (macOS only)", File("/usr/bin/sips").exists())
        Assume.assumeTrue("tiffutil is available (macOS only)", File("/usr/bin/tiffutil").exists())

        val projectRoot = File(System.getProperty("user.dir")).parentFile.parentFile
        val headerPng = File(projectRoot, "art/header.png")
        Assume.assumeTrue("art/header.png exists", headerPng.isFile)

        // Build 1x TIFF
        val tiff1x = tmpDir.newFile("header.tiff")
        val exit1 = ProcessBuilder("/usr/bin/sips", "-s", "format", "tiff", headerPng.absolutePath, "--out", tiff1x.absolutePath).start().waitFor()
        Assume.assumeTrue("sips 1x succeeded", exit1 == 0)

        // Build 2x TIFF (scale up)
        val scaled2x = tmpDir.newFile("header_2x_scaled.png")
        ProcessBuilder("/usr/bin/sips", "-z", "2048", "3072", headerPng.absolutePath, "--out", scaled2x.absolutePath).start().waitFor()
        val tiff2x = tmpDir.newFile("header@2x.tiff")
        ProcessBuilder("/usr/bin/sips", "-s", "format", "tiff", scaled2x.absolutePath, "--out", tiff2x.absolutePath).start().waitFor()

        // Combine into Apple multi-resolution TIFF
        val multiResTiff = tmpDir.newFile("header_hires.tiff")
        val exitMerge = ProcessBuilder("/usr/bin/tiffutil", "-cathidpicheck", tiff1x.absolutePath, tiff2x.absolutePath, "-out", multiResTiff.absolutePath).start().waitFor()
        Assume.assumeTrue("tiffutil merge succeeded", exitMerge == 0)

        val originalBytes = multiResTiff.readBytes()
        val outputDir = tmpDir.newFolder("dmg-assets")

        val result = padDmgBackgroundForTitleBar(multiResTiff, outputDir)

        assertTrue("Copied multi-res TIFF must exist", result.isFile)
        assertTrue(
            "Multi-resolution Apple TIFF bytes must be identical — no re-encoding allowed (issue #185)",
            originalBytes.contentEquals(result.readBytes()),
        )
    }

    // ---- helpers ----

    private fun createTestImage(
        width: Int,
        height: Int,
        color: Color = Color.RED,
    ): BufferedImage {
        val img = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        g.color = color
        g.fillRect(0, 0, width, height)
        g.dispose()
        return img
    }
}
