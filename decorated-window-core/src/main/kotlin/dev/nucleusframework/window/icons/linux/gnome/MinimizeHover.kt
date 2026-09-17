package dev.nucleusframework.window.icons.linux.gnome

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp
import dev.nucleusframework.window.icons.linux.gnome.GnomeControlButtonsIcons

public val GnomeControlButtonsIcons.MinimizeHover: ImageVector
    get() {
        if (_MinimizeHover != null) {
            return _MinimizeHover!!
        }
        _MinimizeHover = ImageVector.Builder(
            name = "MinimizeHover",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            path(
                fill = SolidColor(Color.Black),
                fillAlpha = 0.08f,
                strokeAlpha = 0.08f
            ) {
                moveTo(12f, 12f)
                moveToRelative(-12f, 0f)
                arcToRelative(12f, 12f, 0f, isMoreThanHalf = true, isPositiveArc = true, 24f, 0f)
                arcToRelative(12f, 12f, 0f, isMoreThanHalf = true, isPositiveArc = true, -24f, 0f)
            }
            path(
                stroke = SolidColor(Color(0xFF6C707E)),
                strokeLineWidth = 1f
            ) {
                moveTo(8f, 13.5f)
                horizontalLineTo(16f)
            }
        }.build()

        return _MinimizeHover!!
    }

@Suppress("ObjectPropertyName")
private var _MinimizeHover: ImageVector? = null
