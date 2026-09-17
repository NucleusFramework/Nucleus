package dev.nucleusframework.window.icons.linux.gnome

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp
import dev.nucleusframework.window.icons.linux.gnome.GnomeControlButtonsIcons

public val GnomeControlButtonsIcons.MaximizeInactive: ImageVector
    get() {
        if (_MaximizeInactive != null) {
            return _MaximizeInactive!!
        }
        _MaximizeInactive = ImageVector.Builder(
            name = "MaximizeInactive",
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
                moveTo(8.5f, 8.5f)
                verticalLineTo(15.5f)
                horizontalLineTo(15.5f)
                verticalLineTo(8.5f)
                horizontalLineTo(8.5f)
                close()
            }
        }.build()

        return _MaximizeInactive!!
    }

@Suppress("ObjectPropertyName")
private var _MaximizeInactive: ImageVector? = null
