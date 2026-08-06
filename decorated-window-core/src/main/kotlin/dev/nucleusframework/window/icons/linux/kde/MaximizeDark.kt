package dev.nucleusframework.window.icons.linux.kde

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp
import dev.nucleusframework.window.icons.linux.kde.KdeControlButtonsIcons

public val KdeControlButtonsIcons.MaximizeDark: ImageVector
    get() {
        if (_MaximizeDark != null) {
            return _MaximizeDark!!
        }
        _MaximizeDark = ImageVector.Builder(
            name = "MaximizeDark",
            defaultWidth = 20.dp,
            defaultHeight = 20.dp,
            viewportWidth = 20f,
            viewportHeight = 20f
        ).apply {
            path(
                stroke = SolidColor(Color(0xFFCED0D6)),
                strokeLineWidth = 1f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(15f, 12f)
                lineTo(10f, 7f)
                lineTo(5f, 12f)
            }
        }.build()

        return _MaximizeDark!!
    }

@Suppress("ObjectPropertyName")
private var _MaximizeDark: ImageVector? = null
