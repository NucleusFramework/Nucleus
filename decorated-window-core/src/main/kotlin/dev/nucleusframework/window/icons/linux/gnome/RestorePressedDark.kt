package dev.nucleusframework.window.icons.linux.gnome

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp
import dev.nucleusframework.window.icons.linux.gnome.GnomeControlButtonsIcons

public val GnomeControlButtonsIcons.RestorePressedDark: ImageVector
    get() {
        if (_RestorePressedDark != null) {
            return _RestorePressedDark!!
        }
        _RestorePressedDark = ImageVector.Builder(
            name = "RestorePressedDark",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            path(
                fill = SolidColor(Color.White),
                fillAlpha = 0.1f,
                strokeAlpha = 0.1f
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

        return _RestorePressedDark!!
    }

@Suppress("ObjectPropertyName")
private var _RestorePressedDark: ImageVector? = null
