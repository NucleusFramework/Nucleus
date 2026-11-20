package dev.nucleusframework.lab.probes.rendering.conformance.samples

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import dev.nucleusframework.lab.designsystem.Icon
import dev.nucleusframework.lab.designsystem.LabIcon
import dev.nucleusframework.lab.designsystem.LabTheme
import dev.nucleusframework.lab.designsystem.Text
import dev.nucleusframework.lab.probes.rendering.conformance.SampleCategory
import androidx.compose.ui.graphics.Canvas as BitmapCanvas

/** A 64×40 bitmap drawn in code: a checkerboard with a diagonal, so scaling and orientation show. */
private fun testBitmap(): ImageBitmap {
    val bitmap = ImageBitmap(64, 40)
    val canvas = BitmapCanvas(bitmap)
    val paint = Paint()
    for (y in 0 until 5) {
        for (x in 0 until 8) {
            paint.color = if ((x + y) % 2 == 0) Color(0xFF6366F1) else Color(0xFFF59E0B)
            canvas.drawRect(x * 8f, y * 8f, x * 8f + 8f, y * 8f + 8f, paint)
        }
    }
    paint.color = Color.White
    paint.strokeWidth = 2f
    canvas.drawLine(Offset(0f, 0f), Offset(64f, 40f), paint)
    paint.color = Color(0xFFEC4899)
    canvas.drawCircle(Offset(8f, 32f), 6f, paint)
    return bitmap
}

/** A 24×24 viewport vector from one path, filled: the shape every icon set ships. */
private fun vector(
    name: String,
    pathData: androidx.compose.ui.graphics.vector.PathBuilder.() -> Unit,
): ImageVector =
    ImageVector
        .Builder(name, 24.dp, 24.dp, 24f, 24f)
        .path(fill = SolidColor(Color.Black), pathBuilder = pathData)
        .build()

/** The probe's own vectors: straight edges, curves, a hole (even-odd) and a thin ring, so every edge kind shows. */
private val SampleVectors: List<LabIcon> =
    listOf(
        vector("house") {
            moveTo(12f, 3f)
            lineTo(2f, 12f)
            horizontalLineTo(5f)
            verticalLineTo(20f)
            horizontalLineTo(10f)
            verticalLineTo(14f)
            horizontalLineTo(14f)
            verticalLineTo(20f)
            horizontalLineTo(19f)
            verticalLineTo(12f)
            horizontalLineTo(22f)
            close()
        },
        vector("heart") {
            moveTo(12f, 21f)
            curveTo(5f, 15f, 2f, 12f, 2f, 8f)
            curveTo(2f, 5f, 4.5f, 3f, 7f, 3f)
            curveTo(9f, 3f, 11f, 4.5f, 12f, 6f)
            curveTo(13f, 4.5f, 15f, 3f, 17f, 3f)
            curveTo(19.5f, 3f, 22f, 5f, 22f, 8f)
            curveTo(22f, 12f, 19f, 15f, 12f, 21f)
            close()
        },
        vector("frame") {
            moveTo(3f, 3f)
            horizontalLineTo(21f)
            verticalLineTo(21f)
            horizontalLineTo(3f)
            close()
            moveTo(7f, 7f)
            verticalLineTo(17f)
            horizontalLineTo(17f)
            verticalLineTo(7f)
            close()
        },
        vector("ring") {
            moveTo(12f, 2f)
            arcTo(10f, 10f, 0f, isMoreThanHalf = true, isPositiveArc = true, x1 = 12f, y1 = 22f)
            arcTo(10f, 10f, 0f, isMoreThanHalf = true, isPositiveArc = true, x1 = 12f, y1 = 2f)
            close()
            moveTo(12f, 4f)
            arcTo(8f, 8f, 0f, isMoreThanHalf = true, isPositiveArc = false, x1 = 12f, y1 = 20f)
            arcTo(8f, 8f, 0f, isMoreThanHalf = true, isPositiveArc = false, x1 = 12f, y1 = 4f)
            close()
        },
    ).map(LabIcon::of)

@Composable
private fun Captioned(
    caption: String,
    content: @Composable () -> Unit,
) {
    Column {
        content()
        Text(caption, style = LabTheme.typography.small)
    }
}

@OptIn(ExperimentalLayoutApi::class)
val ImageSamples: List<SampleEntry> =
    samples(SampleCategory.Images) {
        sample(
            "scale",
            "ContentScale",
            "The same 64×40 bitmap: Fit letterboxes, Crop fills and cuts, FillBounds stretches, Inside stays small, None is unscaled. Pink dot bottom-left in every one.",
        ) {
            val bitmap = remember { testBitmap() }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                listOf(
                    "Fit" to ContentScale.Fit,
                    "Crop" to ContentScale.Crop,
                    "FillBounds" to ContentScale.FillBounds,
                    "Inside" to ContentScale.Inside,
                    "None" to ContentScale.None,
                ).forEach { (name, scale) ->
                    Captioned(name) {
                        Image(bitmap, name, Modifier.size(96.dp).border(1.dp, Color.Gray), contentScale = scale)
                    }
                }
            }
        }
        sample(
            "filter",
            "Filtering & colour filters",
            "None upscales with hard pixels, High smoothly; tint turns the bitmap uniformly pink; greyscale removes colour; the circle clip is round.",
        ) {
            val bitmap = remember { testBitmap() }
            val greyscale = remember { ColorMatrix().apply { setToSaturation(0f) } }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Captioned("None") {
                    Image(
                        bitmap,
                        null,
                        Modifier.size(128.dp, 80.dp),
                        contentScale = ContentScale.FillBounds,
                        filterQuality = FilterQuality.None,
                    )
                }
                Captioned("High") {
                    Image(
                        bitmap,
                        null,
                        Modifier.size(128.dp, 80.dp),
                        contentScale = ContentScale.FillBounds,
                        filterQuality = FilterQuality.High,
                    )
                }
                Captioned("tint") {
                    Image(bitmap, null, Modifier.size(96.dp, 60.dp), colorFilter = ColorFilter.tint(Color(0xFFEC4899)))
                }
                Captioned("greyscale") {
                    Image(bitmap, null, Modifier.size(96.dp, 60.dp), colorFilter = ColorFilter.colorMatrix(greyscale))
                }
                Captioned(
                    "clip",
                ) { Image(bitmap, null, Modifier.size(72.dp).clip(CircleShape), contentScale = ContentScale.Crop) }
            }
        }
        sample(
            "vectors",
            "Vector icons",
            "Hand-built vectors (house, heart, framed square with a hole, thin ring) render sharp at 16, 24, 48 and 96 dp, tinted with the accent, no blurry edges at large sizes.",
        ) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                listOf(16, 24, 48, 96).forEach { size ->
                    SampleVectors.forEach { icon ->
                        Icon(icon, null, Modifier.size(size.dp), tint = LabTheme.colors.accent)
                    }
                }
            }
        }
        sample(
            "pixels",
            "Pixel alignment",
            "Hairlines (1 px) and 1 dp lines are solid and evenly spaced at every density lever; no line vanishes or doubles.",
        ) {
            val hairline = LabTheme.colors.text
            Canvas(Modifier.size(240.dp, 80.dp).background(LabTheme.colors.raised)) {
                for (i in 0 until 20) {
                    val x = 6f + i * 12.dp.toPx() / 1.5f
                    drawLine(hairline, Offset(x, 4f), Offset(x, size.height / 2 - 4f), strokeWidth = 0f)
                    drawLine(
                        Color(0xFF6366F1),
                        Offset(x, size.height / 2 + 4f),
                        Offset(x, size.height - 4f),
                        strokeWidth = 1.dp.toPx(),
                    )
                }
            }
        }
    }
