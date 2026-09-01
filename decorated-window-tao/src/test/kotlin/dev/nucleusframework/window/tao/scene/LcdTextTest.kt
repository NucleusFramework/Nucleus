package dev.nucleusframework.window.tao.scene

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.nucleusframework.core.runtime.Platform
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.PixelGeometry
import org.jetbrains.skia.SurfaceProps
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LcdTextTest {
    @Test
    fun `transparent windows disable LCD surface props`() {
        assertNull(
            lcdSurfaceProps(
                windowTransparent = true,
                platform = Platform.Windows,
                windowsLcdGeometry = { PixelGeometry.RGB_H },
            ),
        )
        assertNull(
            lcdSurfaceProps(
                windowTransparent = true,
                platform = Platform.Windows,
                windowsLcdGeometry = { PixelGeometry.BGR_H },
            ),
        )
    }

    @Test
    fun `opaque windows on Windows keep RGB or BGR geometry`() {
        assertNotNull(
            lcdSurfaceProps(
                windowTransparent = false,
                platform = Platform.Windows,
                windowsLcdGeometry = { PixelGeometry.RGB_H },
            ),
        )
        assertNotNull(
            lcdSurfaceProps(
                windowTransparent = false,
                platform = Platform.Windows,
                windowsLcdGeometry = { PixelGeometry.BGR_H },
            ),
        )
    }

    @Test
    fun `macOS and Linux stay grayscale`() {
        assertNull(
            lcdSurfaceProps(
                windowTransparent = false,
                platform = Platform.MacOS,
                windowsLcdGeometry = { PixelGeometry.RGB_H },
            ),
        )
        assertNull(
            lcdSurfaceProps(
                windowTransparent = false,
                platform = Platform.Linux,
                windowsLcdGeometry = { PixelGeometry.RGB_H },
            ),
        )
    }

    @Test
    fun `ClearType off means no LCD surface props`() {
        assertNull(
            lcdSurfaceProps(
                windowTransparent = false,
                platform = Platform.Windows,
                windowsLcdGeometry = { null },
            ),
        )
    }

    @Test
    fun `Compose LCD text on an RGB surface has chromatic edges`() =
        runTaoSceneTest(width = 240, height = 64) {
            setContent {
                Box(Modifier.fillMaxSize().background(Color.White).padding(8.dp)) {
                    Text(
                        "Hamburg",
                        style =
                            taoLcdTextStyle().merge(
                                TextStyle(color = Color.Black, fontSize = 22.sp),
                            ),
                    )
                }
            }
            val lcd =
                renderToBitmap(
                    surfaceProps =
                        SurfaceProps(isDeviceIndependentFonts = false, pixelGeometry = PixelGeometry.RGB_H),
                )
            val gray = renderToBitmap(surfaceProps = SurfaceProps())
            val lcdScore = chromaticScore(lcd)
            val grayScore = chromaticScore(gray)
            assertTrue(
                lcdScore > grayScore,
                "Tao LCD text should fringe on RGB_H (lcd=$lcdScore gray=$grayScore)",
            )
        }
}

private fun chromaticScore(bitmap: Bitmap): Int {
    var score = 0
    for (y in 0 until bitmap.height) {
        for (x in 0 until bitmap.width) {
            val color = bitmap.getColor(x, y)
            val r = (color ushr 16) and 0xFF
            val g = (color ushr 8) and 0xFF
            val b = color and 0xFF
            val spread = maxOf(r, g, b) - minOf(r, g, b)
            if (spread > CHROMA_THRESHOLD) score++
        }
    }
    return score
}

private const val CHROMA_THRESHOLD = 12
