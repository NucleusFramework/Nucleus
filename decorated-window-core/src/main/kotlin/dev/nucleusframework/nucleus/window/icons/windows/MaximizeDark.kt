package dev.nucleusframework.nucleus.window.icons.windows

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp
import dev.nucleusframework.nucleus.window.icons.windows.WindowsControlButtonIcons

val WindowsControlButtonIcons.MaximizeDark: ImageVector
    get() {
        if (_MaximizeDark != null) {
            return _MaximizeDark!!
        }
        _MaximizeDark = ImageVector.Builder(
            name = "MaximizeDark",
            defaultWidth = 16.dp,
            defaultHeight = 16.dp,
            viewportWidth = 16f,
            viewportHeight = 16f
        ).apply {
            path(
                stroke = SolidColor(Color(0xFFCED0D6)),
                strokeLineWidth = 1f
            ) {
                moveTo(3.5f, 3.5f)
                horizontalLineToRelative(9f)
                verticalLineToRelative(9f)
                horizontalLineToRelative(-9f)
                close()
            }
        }.build()

        return _MaximizeDark!!
    }

@Suppress("ObjectPropertyName")
private var _MaximizeDark: ImageVector? = null
