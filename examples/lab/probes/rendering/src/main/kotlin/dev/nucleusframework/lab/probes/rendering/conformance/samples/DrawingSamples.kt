package dev.nucleusframework.lab.probes.rendering.conformance.samples

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import dev.nucleusframework.lab.probes.rendering.conformance.SampleCategory
import kotlin.math.cos
import kotlin.math.sin

private val Indigo = Color(0xFF6366F1)
private val Pink = Color(0xFFEC4899)
private val Cyan = Color(0xFF06B6D4)
private val Amber = Color(0xFFF59E0B)

@OptIn(ExperimentalLayoutApi::class)
val DrawingSamples: List<SampleEntry> =
    samples(SampleCategory.Drawing) {
        sample(
            "shapes",
            "Shapes & borders",
            "Rounded, asymmetric, cut and circle shapes: borders follow the outline exactly, edges antialiased, no seam between fill and border.",
        ) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                listOf(
                    RoundedCornerShape(16.dp),
                    RoundedCornerShape(topStart = 32.dp, bottomEnd = 32.dp),
                    CutCornerShape(14.dp),
                    CircleShape,
                ).forEach { shape ->
                    Box(Modifier.size(72.dp).background(Indigo, shape).border(3.dp, Amber, shape))
                }
            }
        }
        sample(
            "paths",
            "Paths, caps, joins, dashes",
            "A five-point star, a cubic curve, three caps (butt/round/square) and three joins (miter/round/bevel), and a dashed stroke with even gaps.",
        ) {
            Canvas(Modifier.size(320.dp, 200.dp)) {
                val star = Path()
                val center = Offset(60f, 70f)
                for (i in 0 until 10) {
                    val radius = if (i % 2 == 0) 55f else 22f
                    val angle = Math.PI / 5 * i - Math.PI / 2
                    val point =
                        Offset(
                            center.x + radius * cos(angle).toFloat(),
                            center.y + radius * sin(angle).toFloat(),
                        )
                    if (i == 0) star.moveTo(point.x, point.y) else star.lineTo(point.x, point.y)
                }
                star.close()
                drawPath(star, Amber)
                drawPath(star, Indigo, style = Stroke(width = 3f))

                val curve =
                    Path().apply {
                        moveTo(140f, 120f)
                        cubicTo(170f, 0f, 240f, 200f, 300f, 40f)
                    }
                drawPath(curve, Pink, style = Stroke(width = 4f))

                listOf(StrokeCap.Butt, StrokeCap.Round, StrokeCap.Square).forEachIndexed { i, cap ->
                    drawLine(
                        Cyan,
                        Offset(20f, 150f + i * 16f),
                        Offset(110f, 150f + i * 16f),
                        strokeWidth = 10f,
                        cap = cap,
                    )
                }
                listOf(StrokeJoin.Miter, StrokeJoin.Round, StrokeJoin.Bevel).forEachIndexed { i, join ->
                    val x = 140f + i * 55f
                    val zig =
                        Path().apply {
                            moveTo(x, 190f)
                            lineTo(x + 20f, 150f)
                            lineTo(x + 40f, 190f)
                        }
                    drawPath(zig, Indigo, style = Stroke(width = 10f, join = join))
                }
                drawLine(
                    Color.Gray,
                    Offset(140f, 135f),
                    Offset(310f, 135f),
                    strokeWidth = 3f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f)),
                )
            }
        }
        sample(
            "brushes",
            "Gradients & tile modes",
            "Linear, radial and sweep gradients are smooth (no banding steps); the three small boxes repeat, mirror and clamp the same gradient.",
        ) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(Modifier.size(96.dp).background(Brush.linearGradient(listOf(Indigo, Pink, Amber))))
                Box(Modifier.size(96.dp).background(Brush.radialGradient(listOf(Cyan, Indigo, Color.Black))))
                Box(Modifier.size(96.dp).background(Brush.sweepGradient(listOf(Indigo, Pink, Amber, Cyan, Indigo))))
                listOf(TileMode.Repeated, TileMode.Mirror, TileMode.Clamp).forEach { mode ->
                    Box(
                        Modifier
                            .size(
                                64.dp,
                            ).background(
                                Brush.horizontalGradient(
                                    listOf(Indigo, Amber),
                                    startX = 0f,
                                    endX = 40f,
                                    tileMode = mode,
                                ),
                            ),
                    )
                }
            }
        }
        sample(
            "canvas",
            "Canvas primitives & blend modes",
            "Arcs (filled and stroked), rounded rect, points; the overlapping circles show Multiply, Screen and Xor distinctly.",
        ) {
            Canvas(Modifier.size(320.dp, 160.dp)) {
                drawArc(Indigo, 0f, 270f, useCenter = true, topLeft = Offset(10f, 10f), size = Size(90f, 90f))
                drawArc(
                    Pink,
                    45f,
                    200f,
                    useCenter = false,
                    topLeft = Offset(10f, 10f),
                    size = Size(90f, 90f),
                    style = Stroke(6f),
                )
                drawRoundRect(Cyan, Offset(120f, 10f), Size(80f, 60f), CornerRadius(14f))
                drawPoints(
                    List(12) {
                        Offset(120f + it * 7f, 90f + (it % 3) * 6f)
                    },
                    androidx.compose.ui.graphics.PointMode.Points,
                    Amber,
                    strokeWidth = 5f,
                    cap = StrokeCap.Round,
                )
                listOf(BlendMode.Multiply, BlendMode.Screen, BlendMode.Xor).forEachIndexed { i, mode ->
                    val x = 40f + i * 100f
                    drawCircle(Indigo, 26f, Offset(x, 130f))
                    drawCircle(Amber, 26f, Offset(x + 22f, 130f), blendMode = mode)
                }
            }
        }
    }
