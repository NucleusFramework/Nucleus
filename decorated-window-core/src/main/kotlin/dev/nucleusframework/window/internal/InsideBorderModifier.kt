package dev.nucleusframework.window.internal

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Draws a [width]-thick stroke fully inside this node's bounds, following
 * [shape].
 *
 * Unlike a centred stroke, the outer edge sits on the shape outline so
 * nothing is clipped by a parent clip or by a platform window clip
 * (Win11 DWM rounded HWND, Linux XShape / Skia carve). Corner radii shrink
 * by half the stroke so the outline stays concentric with [shape].
 */
public fun Modifier.insideBorder(
    width: Dp = 1.dp,
    color: Color,
    shape: Shape = RectangleShape,
): Modifier =
    drawWithContent {
        drawContent()
        val strokeWidth = width.toPx()
        if (strokeWidth <= 0f ||
            color == Color.Transparent ||
            size.width <= strokeWidth ||
            size.height <= strokeWidth
        ) {
            return@drawWithContent
        }

        val halfStroke = strokeWidth / 2f
        val outline = shape.createOutline(size, layoutDirection, this)
        val clip = Path().apply { addOutline(outline) }
        clipPath(clip) {
            when (outline) {
                is Outline.Rectangle ->
                    drawRect(
                        color = color,
                        topLeft = Offset(halfStroke, halfStroke),
                        size = Size(size.width - strokeWidth, size.height - strokeWidth),
                        style = Stroke(width = strokeWidth),
                    )
                is Outline.Rounded -> {
                    val rr = outline.roundRect
                    val inset =
                        RoundRect(
                            left = rr.left + halfStroke,
                            top = rr.top + halfStroke,
                            right = rr.right - halfStroke,
                            bottom = rr.bottom - halfStroke,
                            topLeftCornerRadius = shrinkCorner(rr.topLeftCornerRadius, halfStroke),
                            topRightCornerRadius = shrinkCorner(rr.topRightCornerRadius, halfStroke),
                            bottomRightCornerRadius =
                                shrinkCorner(rr.bottomRightCornerRadius, halfStroke),
                            bottomLeftCornerRadius =
                                shrinkCorner(rr.bottomLeftCornerRadius, halfStroke),
                        )
                    drawPath(
                        path = Path().apply { addRoundRect(inset) },
                        color = color,
                        style = Stroke(width = strokeWidth),
                    )
                }
                is Outline.Generic -> {
                    val insetSize = Size(size.width - strokeWidth, size.height - strokeWidth)
                    val insetOutline = shape.createOutline(insetSize, layoutDirection, this)
                    val insetPath = Path().apply { addOutline(insetOutline) }
                    insetPath.translate(Offset(halfStroke, halfStroke))
                    drawPath(
                        path = insetPath,
                        color = color,
                        style = Stroke(width = strokeWidth),
                    )
                }
            }
        }
    }

private fun shrinkCorner(
    corner: CornerRadius,
    inset: Float,
): CornerRadius =
    CornerRadius(
        (corner.x - inset).coerceAtLeast(0f),
        (corner.y - inset).coerceAtLeast(0f),
    )
