package dev.nucleusframework.window.icons.windows

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp
import dev.nucleusframework.window.icons.windows.WindowsControlButtonIcons

public val WindowsControlButtonIcons.CloseDark: ImageVector
    get() {
        if (_CloseDark != null) {
            return _CloseDark!!
        }
        _CloseDark = ImageVector.Builder(
            name = "CloseDark",
            defaultWidth = 16.dp,
            defaultHeight = 16.dp,
            viewportWidth = 16f,
            viewportHeight = 16f
        ).apply {
            path(fill = SolidColor(Color(0xFFCED0D6))) {
                moveTo(3.758f, 3.05f)
                lineToRelative(9.192f, 9.192f)
                lineToRelative(-0.707f, 0.707f)
                lineToRelative(-9.192f, -9.192f)
                close()
            }
            path(fill = SolidColor(Color(0xFFCED0D6))) {
                moveTo(12.243f, 3.05f)
                lineToRelative(-9.192f, 9.192f)
                lineToRelative(0.707f, 0.707f)
                lineToRelative(9.192f, -9.192f)
                close()
            }
        }.build()

        return _CloseDark!!
    }

@Suppress("ObjectPropertyName")
private var _CloseDark: ImageVector? = null
