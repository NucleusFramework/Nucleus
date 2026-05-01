package io.github.kdroidfilter.nucleus.window.internal

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

fun Modifier.insideBorder(
    width: Dp = 1.dp,
    color: Color,
    shape: Shape = RectangleShape,
): Modifier =
    drawWithContent {
        drawContent()
        val strokeWidth = width.toPx()
        if (strokeWidth <= 0f || color == Color.Transparent) return@drawWithContent

        val halfStroke = strokeWidth / 2f
        val insetSize = Size(size.width - strokeWidth, size.height - strokeWidth)
        val outline = shape.createOutline(insetSize, layoutDirection, this)

        translate(halfStroke, halfStroke) {
            drawOutline(
                outline = outline,
                color = color,
                style = Stroke(width = strokeWidth),
            )
        }
    }
