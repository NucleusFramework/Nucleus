package dev.nucleusframework.nucleus.window.icons.linux.kde

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp
import dev.nucleusframework.nucleus.window.icons.linux.kde.KdeControlButtonsIcons

val KdeControlButtonsIcons.MinimizeDark: ImageVector
    get() {
        if (_MinimizeDark != null) {
            return _MinimizeDark!!
        }
        _MinimizeDark = ImageVector.Builder(
            name = "MinimizeDark",
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
                moveTo(15f, 8f)
                lineTo(10f, 13f)
                lineTo(5f, 8f)
            }
        }.build()

        return _MinimizeDark!!
    }

@Suppress("ObjectPropertyName")
private var _MinimizeDark: ImageVector? = null
