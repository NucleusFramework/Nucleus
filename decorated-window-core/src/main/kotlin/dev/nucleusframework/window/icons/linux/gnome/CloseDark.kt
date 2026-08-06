package dev.nucleusframework.window.icons.linux.gnome

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp
import dev.nucleusframework.window.icons.linux.gnome.GnomeControlButtonsIcons

public val GnomeControlButtonsIcons.CloseDark: ImageVector
    get() {
        if (_CloseDark != null) {
            return _CloseDark!!
        }
        _CloseDark = ImageVector.Builder(
            name = "CloseDark",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            path(
                fill = SolidColor(Color.White),
                fillAlpha = 0.05f,
                strokeAlpha = 0.05f
            ) {
                moveTo(12f, 12f)
                moveToRelative(-12f, 0f)
                arcToRelative(12f, 12f, 0f, isMoreThanHalf = true, isPositiveArc = true, 24f, 0f)
                arcToRelative(12f, 12f, 0f, isMoreThanHalf = true, isPositiveArc = true, -24f, 0f)
            }
            path(
                stroke = SolidColor(Color(0xFFCED0D6)),
                strokeLineWidth = 1f,
                strokeLineCap = StrokeCap.Round
            ) {
                moveTo(15.5f, 15.5f)
                lineTo(8.5f, 8.5f)
                moveTo(15.5f, 8.5f)
                lineTo(8.5f, 15.5f)
            }
        }.build()

        return _CloseDark!!
    }

@Suppress("ObjectPropertyName")
private var _CloseDark: ImageVector? = null
