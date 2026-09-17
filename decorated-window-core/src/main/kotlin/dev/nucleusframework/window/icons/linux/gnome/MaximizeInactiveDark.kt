package dev.nucleusframework.window.icons.linux.gnome

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp
import dev.nucleusframework.window.icons.linux.gnome.GnomeControlButtonsIcons

public val GnomeControlButtonsIcons.MaximizeInactiveDark: ImageVector
    get() {
        if (_MaximizeInactiveDark != null) {
            return _MaximizeInactiveDark!!
        }
        _MaximizeInactiveDark = ImageVector.Builder(
            name = "MaximizeInactiveDark",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            path(
                stroke = SolidColor(Color(0xFFCED0D6)),
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

        return _MaximizeInactiveDark!!
    }

@Suppress("ObjectPropertyName")
private var _MaximizeInactiveDark: ImageVector? = null
