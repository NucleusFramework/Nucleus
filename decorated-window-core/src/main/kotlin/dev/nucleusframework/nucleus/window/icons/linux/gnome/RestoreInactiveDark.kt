package dev.nucleusframework.nucleus.window.icons.linux.gnome

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp
import dev.nucleusframework.nucleus.window.icons.linux.gnome.GnomeControlButtonsIcons

val GnomeControlButtonsIcons.RestoreInactiveDark: ImageVector
    get() {
        if (_RestoreInactiveDark != null) {
            return _RestoreInactiveDark!!
        }
        _RestoreInactiveDark = ImageVector.Builder(
            name = "RestoreInactiveDark",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
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

        return _RestoreInactiveDark!!
    }

@Suppress("ObjectPropertyName")
private var _RestoreInactiveDark: ImageVector? = null
