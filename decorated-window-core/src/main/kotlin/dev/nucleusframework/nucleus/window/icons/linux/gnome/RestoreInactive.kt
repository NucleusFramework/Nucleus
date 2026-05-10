package dev.nucleusframework.nucleus.window.icons.linux.gnome

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp
import dev.nucleusframework.nucleus.window.icons.linux.gnome.GnomeControlButtonsIcons

val GnomeControlButtonsIcons.RestoreInactive: ImageVector
    get() {
        if (_RestoreInactive != null) {
            return _RestoreInactive!!
        }
        _RestoreInactive = ImageVector.Builder(
            name = "RestoreInactive",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            path(
                stroke = SolidColor(Color(0xFF6C707E)),
                strokeLineWidth = 1f,
                strokeLineCap = StrokeCap.Round
            ) {
                moveTo(8.5f, 9.5f)
                verticalLineTo(15.5f)
                horizontalLineTo(14.5f)
                verticalLineTo(9.5f)
                horizontalLineTo(8.5f)
                close()
            }
            path(
                stroke = SolidColor(Color(0xFF6C707E)),
                strokeLineWidth = 1f
            ) {
                moveTo(16.5f, 14f)
                verticalLineTo(7.5f)
                horizontalLineTo(10f)
            }
        }.build()

        return _RestoreInactive!!
    }

@Suppress("ObjectPropertyName")
private var _RestoreInactive: ImageVector? = null
