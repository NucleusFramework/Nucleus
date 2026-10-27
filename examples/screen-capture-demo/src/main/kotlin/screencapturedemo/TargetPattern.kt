package screencapturedemo

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import dev.nucleusframework.screencapture.ScreenImage

/**
 * A pattern laid out in physical pixels, so a capture can be checked pixel for pixel whatever
 * the display scale: a marker block that locates it, a grid of distinct colours, a 1-pixel
 * checkerboard (any resampling blurs it) and a ramp through every channel value.
 */
internal object TargetPattern {
    const val WIDTH = 256
    const val HEIGHT = 160
    const val MARKER = 0xFF123456.toInt()
    private const val MARKER_SIZE = 24
    private const val CELL = 24
    private const val GRID_TOP = 32
    private const val COLUMNS = 8
    private const val ROWS = 4
    private const val CHECKER_TOP = 136
    private const val RAMP_TOP = 148
    private const val BAND = 8

    /** The expected pixel at ([x], [y]) of the pattern; `null` for the unpainted background. */
    fun expected(
        x: Int,
        y: Int,
    ): Int? =
        when {
            x < MARKER_SIZE && y < MARKER_SIZE -> MARKER
            y in GRID_TOP until GRID_TOP + ROWS * CELL && x < COLUMNS * CELL ->
                cellColor((x / CELL), (y - GRID_TOP) / CELL)
            y in CHECKER_TOP until CHECKER_TOP + BAND -> if ((x + y) % 2 == 0) BLACK else WHITE
            y in RAMP_TOP until RAMP_TOP + BAND -> ramp(x)
            else -> null
        }

    private fun cellColor(
        column: Int,
        row: Int,
    ): Int {
        val r = (column * 33 + row * 7) and 0xFF
        val g = (255 - row * 61 - column * 3) and 0xFF
        val b = (column * 91 + row * 45 + 17) and 0xFF
        return argb(r, g, b)
    }

    private fun ramp(x: Int): Int = argb(x, 255 - x, (x * 7) and 0xFF)

    private fun argb(
        r: Int,
        g: Int,
        b: Int,
    ): Int = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    private const val BLACK = 0xFF000000.toInt()
    private const val WHITE = 0xFFFFFFFF.toInt()

    /** Top-left corners of every pattern found in [image]. */
    fun find(image: ScreenImage): List<Pair<Int, Int>> {
        val pixels = image.toArgbArray()
        val found = mutableListOf<Pair<Int, Int>>()
        val w = image.width
        for (y in 0..image.height - MARKER_SIZE) {
            for (x in 0..w - MARKER_SIZE) {
                if (pixels[y * w + x] != MARKER) continue
                if (x > 0 && pixels[y * w + x - 1] == MARKER) continue
                if (y > 0 && pixels[(y - 1) * w + x] == MARKER) continue
                if (pixels[y * w + x + MARKER_SIZE - 1] == MARKER && pixels[(y + MARKER_SIZE - 1) * w + x] == MARKER) {
                    found += x to y
                }
            }
        }
        return found
    }

    /** Pixels of the pattern at ([ox], [oy]) of [image] that differ from [expected]; the first ones described. */
    fun mismatches(
        image: ScreenImage,
        ox: Int,
        oy: Int,
    ): Pair<Int, List<String>> {
        if (ox + WIDTH > image.width || oy + HEIGHT > image.height) return WIDTH * HEIGHT to listOf("pattern clipped")
        var count = 0
        val samples = mutableListOf<String>()
        for (y in 0 until HEIGHT) {
            for (x in 0 until WIDTH) {
                val want = expected(x, y) ?: continue
                val got = image.pixelAt(ox + x, oy + y)
                if (got != want) {
                    count++
                    if (samples.size < 5) samples += "($x,$y) want %08X got %08X".format(want, got)
                }
            }
        }
        return count to samples
    }

    @Composable
    fun Content(modifier: Modifier = Modifier) {
        val density = LocalDensity.current
        val size =
            with(density) {
                androidx.compose.ui.unit
                    .DpSize(WIDTH.toDp(), HEIGHT.toDp())
            }
        Canvas(modifier.size(size)) {
            fun px(
                x: Int,
                y: Int,
                w: Int,
                h: Int,
                color: Int,
            ) = drawRect(Color(color), Offset(x.toFloat(), y.toFloat()), Size(w.toFloat(), h.toFloat()))
            px(0, 0, MARKER_SIZE, MARKER_SIZE, MARKER)
            for (row in 0 until ROWS) {
                for (column in 0 until COLUMNS) {
                    px(
                        column * CELL,
                        GRID_TOP + row * CELL,
                        CELL,
                        CELL,
                        cellColor(column, row),
                    )
                }
            }
            px(0, CHECKER_TOP, WIDTH, BAND, WHITE)
            for (y in CHECKER_TOP until CHECKER_TOP + BAND) {
                for (x in 0 until WIDTH) if ((x + y) % 2 == 0) px(x, y, 1, 1, BLACK)
            }
            for (x in 0 until WIDTH) px(x, RAMP_TOP, 1, BAND, ramp(x))
        }
    }
}
