package dev.nucleusframework.window.icons.linux.gnome

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp
import dev.nucleusframework.window.icons.linux.gnome.GnomeControlButtonsIcons

public val GnomeControlButtonsIcons.MinimizeInactiveDark: ImageVector
    get() {
        if (_MinimizeInactiveDark != null) {
            return _MinimizeInactiveDark!!
        }
        _MinimizeInactiveDark = ImageVector.Builder(
            name = "MinimizeInactiveDark",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            path(
                stroke = SolidColor(Color(0xFFCED0D6)),
                strokeLineWidth = 1f
            ) {
                moveTo(8f, 13.5f)
                horizontalLineTo(16f)
            }
        }.build()

        return _MinimizeInactiveDark!!
    }

@Suppress("ObjectPropertyName")
private var _MinimizeInactiveDark: ImageVector? = null
