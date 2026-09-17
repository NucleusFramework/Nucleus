package dev.nucleusframework.window.icons.linux.kde

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp
import dev.nucleusframework.window.icons.linux.kde.KdeControlButtonsIcons

public val KdeControlButtonsIcons.MinimizeHoverDark: ImageVector
    get() {
        if (_MinimizeHoverDark != null) {
            return _MinimizeHoverDark!!
        }
        _MinimizeHoverDark = ImageVector.Builder(
            name = "MinimizeHoverDark",
            defaultWidth = 20.dp,
            defaultHeight = 20.dp,
            viewportWidth = 20f,
            viewportHeight = 20f
        ).apply {
            path(fill = SolidColor(Color(0xFFDFE1E6))) {
                moveTo(10f, 10f)
                moveToRelative(-9f, 0f)
                arcToRelative(9f, 9f, 0f, isMoreThanHalf = true, isPositiveArc = true, 18f, 0f)
                arcToRelative(9f, 9f, 0f, isMoreThanHalf = true, isPositiveArc = true, -18f, 0f)
            }
            path(
                stroke = SolidColor(Color(0xFF393B40)),
                strokeLineWidth = 1f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(15f, 8f)
                lineTo(10f, 13f)
                lineTo(5f, 8f)
            }
        }.build()

        return _MinimizeHoverDark!!
    }

@Suppress("ObjectPropertyName")
private var _MinimizeHoverDark: ImageVector? = null
