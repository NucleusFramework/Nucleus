package dev.nucleusframework.nucleus.window.icons.linux.kde

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp
import dev.nucleusframework.nucleus.window.icons.linux.kde.KdeControlButtonsIcons

val KdeControlButtonsIcons.Restore: ImageVector
    get() {
        if (_Restore != null) {
            return _Restore!!
        }
        _Restore = ImageVector.Builder(
            name = "Restore",
            defaultWidth = 20.dp,
            defaultHeight = 20.dp,
            viewportWidth = 20f,
            viewportHeight = 20f
        ).apply {
            path(
                stroke = SolidColor(Color(0xFF232629)),
                strokeLineWidth = 1f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(14.5f, 10f)
                lineTo(10f, 5.5f)
                lineTo(5.5f, 10f)
                lineTo(10f, 14.5f)
                lineTo(14.5f, 10f)
                close()
            }
        }.build()

        return _Restore!!
    }

@Suppress("ObjectPropertyName")
private var _Restore: ImageVector? = null
