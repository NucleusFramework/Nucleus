package dev.nucleusframework.window.icons.linux.gnome

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp
import dev.nucleusframework.window.icons.linux.gnome.GnomeControlButtonsIcons

public val GnomeControlButtonsIcons.MinimizeDark: ImageVector
    get() {
        if (_MinimizeDark != null) {
            return _MinimizeDark!!
        }
        _MinimizeDark = ImageVector.Builder(
            name = "MinimizeDark",
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
                strokeLineWidth = 1f
            ) {
                moveTo(8f, 13.5f)
                horizontalLineTo(16f)
            }
        }.build()

        return _MinimizeDark!!
    }

@Suppress("ObjectPropertyName")
private var _MinimizeDark: ImageVector? = null
