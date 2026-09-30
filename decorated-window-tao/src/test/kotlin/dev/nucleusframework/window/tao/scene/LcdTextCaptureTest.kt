package dev.nucleusframework.window.tao.scene

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.PixelGeometry
import org.jetbrains.skia.SurfaceProps
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Writes a GitHub-issue-style side-by-side zoom of grayscale vs ClearType text.
 * Output: `decorated-window-tao/build/lcd-text-comparison.png`
 */
class LcdTextCaptureTest {
    @Test
    fun `write zoomed grayscale vs LCD comparison png`() {
        val gray = renderText(lcd = false)
        val lcd = renderText(lcd = true)
        val out = writeComparison(gray, lcd)
        assertTrue(Files.exists(out) && Files.size(out) > 0L, "missing $out")
        println("LCD comparison written to $out")
    }
}

private fun renderText(lcd: Boolean): BufferedImage {
    lateinit var bitmap: Bitmap
    runTaoSceneTest(width = SAMPLE_WIDTH, height = SAMPLE_HEIGHT) {
        setContent {
            SampleLines(lcd)
        }
        bitmap =
            renderToBitmap(
                surfaceProps =
                    if (lcd) {
                        SurfaceProps(isDeviceIndependentFonts = false, pixelGeometry = PixelGeometry.RGB_H)
                    } else {
                        SurfaceProps()
                    },
            )
    }
    return bitmap.toBufferedImage()
}

@Composable
private fun SampleLines(lcd: Boolean) {
    Box(Modifier.fillMaxSize().background(Color.White).padding(12.dp)) {
        Column {
            Text("File   Edit   View   Help", style = sampleStyle(lcd, 13.sp, FontWeight.Normal))
            Text("The five boxing wizards jump", style = sampleStyle(lcd, 14.sp, FontWeight.Normal))
            Text("fun main() { println(\"Hello\") }", style = sampleStyle(lcd, 13.sp, FontWeight.Normal))
        }
    }
}

private fun sampleStyle(
    lcd: Boolean,
    size: androidx.compose.ui.unit.TextUnit,
    weight: FontWeight,
): TextStyle {
    val base = TextStyle(color = Color.Black, fontSize = size, fontWeight = weight)
    return if (lcd) taoLcdTextStyle().merge(base) else base
}

private fun writeComparison(
    gray: BufferedImage,
    lcd: BufferedImage,
): Path {
    val crop = cropToContent(gray).union(cropToContent(lcd))
    val grayCrop = gray.getSubimage(crop.x, crop.y, crop.w, crop.h)
    val lcdCrop = lcd.getSubimage(crop.x, crop.y, crop.w, crop.h)
    val zoomedGray = nearestZoom(grayCrop, ZOOM)
    val zoomedLcd = nearestZoom(lcdCrop, ZOOM)

    val labelH = 36
    val gap = 16
    val panelW = zoomedGray.width
    val panelH = zoomedGray.height
    val outW = panelW * 2 + gap + 32
    val outH = labelH + panelH + 24
    val out = BufferedImage(outW, outH, BufferedImage.TYPE_INT_RGB)
    val g = out.createGraphics()
    g.color = java.awt.Color(0xF3, 0xF3, 0xF3)
    g.fillRect(0, 0, outW, outH)
    g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
    g.font = Font("Segoe UI", Font.BOLD, 16)
    g.color = java.awt.Color(0x33, 0x33, 0x33)
    g.drawString("Grayscale (avant — #875)", 16, 24)
    g.drawString("ClearType LCD (Tao)", 16 + panelW + gap, 24)
    g.drawImage(zoomedGray, 16, labelH, null)
    g.drawImage(zoomedLcd, 16 + panelW + gap, labelH, null)
    g.dispose()

    val path = Path.of(System.getProperty("user.dir"), "build", "lcd-text-comparison.png")
    Files.createDirectories(path.parent)
    ImageIO.write(out, "png", path.toFile())
    return path
}

private fun Bitmap.toBufferedImage(): BufferedImage {
    val img = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
    for (y in 0 until height) {
        for (x in 0 until width) {
            img.setRGB(x, y, getColor(x, y))
        }
    }
    return img
}

private data class Crop(
    val x: Int,
    val y: Int,
    val w: Int,
    val h: Int,
) {
    fun union(other: Crop): Crop {
        val left = minOf(x, other.x)
        val top = minOf(y, other.y)
        val right = maxOf(x + w, other.x + other.w)
        val bottom = maxOf(y + h, other.y + other.h)
        return Crop(left, top, right - left, bottom - top)
    }
}

private fun cropToContent(img: BufferedImage): Crop {
    var minX = img.width
    var minY = img.height
    var maxX = 0
    var maxY = 0
    for (y in 0 until img.height) {
        for (x in 0 until img.width) {
            if (img.getRGB(x, y) and 0x00FFFFFF != 0x00FFFFFF) {
                if (x < minX) minX = x
                if (y < minY) minY = y
                if (x > maxX) maxX = x
                if (y > maxY) maxY = y
            }
        }
    }
    val pad = 4
    val x = (minX - pad).coerceAtLeast(0)
    val y = (minY - pad).coerceAtLeast(0)
    val w = (maxX + pad + 1 - x).coerceAtMost(img.width - x)
    val h = (maxY + pad + 1 - y).coerceAtMost(img.height - y)
    return Crop(x, y, w, h)
}

private fun nearestZoom(
    src: BufferedImage,
    zoom: Int,
): BufferedImage {
    val dst = BufferedImage(src.width * zoom, src.height * zoom, BufferedImage.TYPE_INT_RGB)
    val g = dst.createGraphics()
    g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR)
    g.drawImage(src, 0, 0, dst.width, dst.height, null)
    g.dispose()
    return dst
}

private const val SAMPLE_WIDTH = 420
private const val SAMPLE_HEIGHT = 110
private const val ZOOM = 8
