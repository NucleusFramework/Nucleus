package dev.nucleusframework.window.icons.windows

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp
import dev.nucleusframework.window.icons.windows.WindowsControlButtonIcons

public val WindowsControlButtonIcons.MinimizeInactiveDark: ImageVector
    get() {
        if (_MinimizeInactiveDark != null) {
            return _MinimizeInactiveDark!!
        }
        _MinimizeInactiveDark = ImageVector.Builder(
            name = "MinimizeInactiveDark",
            defaultWidth = 16.dp,
            defaultHeight = 16.dp,
            viewportWidth = 16f,
            viewportHeight = 16f
        ).apply {
            path(fill = SolidColor(Color(0xFF6F737A))) {
                moveTo(3f, 8f)
                horizontalLineToRelative(10f)
                verticalLineToRelative(1f)
                horizontalLineToRelative(-10f)
                close()
            }
        }.build()

        return _MinimizeInactiveDark!!
    }

@Suppress("ObjectPropertyName")
private var _MinimizeInactiveDark: ImageVector? = null
