package dev.nucleusframework.window.icons.windows

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp
import dev.nucleusframework.window.icons.windows.WindowsControlButtonIcons

public val WindowsControlButtonIcons.MaximizeInactiveDark: ImageVector
    get() {
        if (_MaximizeInactiveDark != null) {
            return _MaximizeInactiveDark!!
        }
        _MaximizeInactiveDark = ImageVector.Builder(
            name = "MaximizeInactiveDark",
            defaultWidth = 16.dp,
            defaultHeight = 16.dp,
            viewportWidth = 16f,
            viewportHeight = 16f
        ).apply {
            path(
                stroke = SolidColor(Color(0xFF6F737A)),
                strokeLineWidth = 1f
            ) {
                moveTo(3.5f, 3.5f)
                horizontalLineToRelative(9f)
                verticalLineToRelative(9f)
                horizontalLineToRelative(-9f)
                close()
            }
        }.build()

        return _MaximizeInactiveDark!!
    }

@Suppress("ObjectPropertyName")
private var _MaximizeInactiveDark: ImageVector? = null
