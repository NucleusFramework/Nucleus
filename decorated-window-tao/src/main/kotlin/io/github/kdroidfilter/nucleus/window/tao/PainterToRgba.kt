package io.github.kdroidfilter.nucleus.window.tao

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageBitmapConfig
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope

/**
 * Rasterises a [Painter] into a row-major premultiplied RGBA byte array suitable
 * for `tao::window::Icon::from_rgba`. Returns `null` if the painter has no
 * intrinsic size — Tao requires explicit dimensions.
 */
internal fun Painter.toRgbaIcon(
    sizePx: Int = 64,
): Triple<Int, Int, ByteArray>? {
    val bitmap = ImageBitmap(sizePx, sizePx, ImageBitmapConfig.Argb8888)
    val canvas = Canvas(bitmap)
    val drawScope = CanvasDrawScope()
    drawScope.draw(
        density = Density(1f),
        layoutDirection = LayoutDirection.Ltr,
        canvas = canvas,
        size = Size(sizePx.toFloat(), sizePx.toFloat()),
    ) {
        with(this@toRgbaIcon) {
            draw(size = Size(sizePx.toFloat(), sizePx.toFloat()))
        }
    }
    val pixelMap = bitmap.toPixelMap()
    val out = ByteArray(sizePx * sizePx * 4)
    var i = 0
    for (y in 0 until sizePx) {
        for (x in 0 until sizePx) {
            val color = pixelMap[x, y]
            // Compose `Color` is straight (non-premultiplied). Tao expects
            // premultiplied RGBA — multiply each channel by alpha.
            val a = color.alpha
            out[i++] = (color.red * a * 255f).toInt().coerceIn(0, 255).toByte()
            out[i++] = (color.green * a * 255f).toInt().coerceIn(0, 255).toByte()
            out[i++] = (color.blue * a * 255f).toInt().coerceIn(0, 255).toByte()
            out[i++] = (a * 255f).toInt().coerceIn(0, 255).toByte()
        }
    }
    return Triple(sizePx, sizePx, out)
}
