package dev.nucleusframework.nucleus.window.icons.windows

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp
import dev.nucleusframework.nucleus.window.icons.windows.WindowsControlButtonIcons

val WindowsControlButtonIcons.MinimizeDark: ImageVector
    get() {
        if (_MinimizeDark != null) {
            return _MinimizeDark!!
        }
        _MinimizeDark = ImageVector.Builder(
            name = "MinimizeDark",
            defaultWidth = 16.dp,
            defaultHeight = 16.dp,
            viewportWidth = 16f,
            viewportHeight = 16f
        ).apply {
            path(fill = SolidColor(Color(0xFFCED0D6))) {
                moveTo(3f, 8f)
                horizontalLineToRelative(10f)
                verticalLineToRelative(1f)
                horizontalLineToRelative(-10f)
                close()
            }
        }.build()

        return _MinimizeDark!!
    }

@Suppress("ObjectPropertyName")
private var _MinimizeDark: ImageVector? = null
