package dev.nucleusframework.nucleus.window.icons.linux.gnome

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp
import dev.nucleusframework.nucleus.window.icons.linux.gnome.GnomeControlButtonsIcons

val GnomeControlButtonsIcons.RestoreDark: ImageVector
    get() {
        if (_RestoreDark != null) {
            return _RestoreDark!!
        }
        _RestoreDark = ImageVector.Builder(
            name = "RestoreDark",
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
                moveTo(8.5f, 9.5f)
                verticalLineTo(15.5f)
                horizontalLineTo(14.5f)
                verticalLineTo(9.5f)
                horizontalLineTo(8.5f)
                close()
            }
            path(
                stroke = SolidColor(Color(0xFFCED0D6)),
                strokeLineWidth = 1f
            ) {
                moveTo(16.5f, 14f)
                verticalLineTo(7.5f)
                horizontalLineTo(10f)
            }
        }.build()

        return _RestoreDark!!
    }

@Suppress("ObjectPropertyName")
private var _RestoreDark: ImageVector? = null
