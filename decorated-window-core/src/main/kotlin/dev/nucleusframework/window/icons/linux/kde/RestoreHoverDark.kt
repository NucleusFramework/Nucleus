package dev.nucleusframework.window.icons.linux.kde

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp
import dev.nucleusframework.window.icons.linux.kde.KdeControlButtonsIcons

public val KdeControlButtonsIcons.RestoreHoverDark: ImageVector
    get() {
        if (_RestoreHoverDark != null) {
            return _RestoreHoverDark!!
        }
        _RestoreHoverDark = ImageVector.Builder(
            name = "RestoreHoverDark",
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
                moveTo(14.5f, 10f)
                lineTo(10f, 5.5f)
                lineTo(5.5f, 10f)
                lineTo(10f, 14.5f)
                lineTo(14.5f, 10f)
                close()
            }
        }.build()

        return _RestoreHoverDark!!
    }

@Suppress("ObjectPropertyName")
private var _RestoreHoverDark: ImageVector? = null
