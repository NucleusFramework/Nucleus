package dev.nucleusframework.nucleus.window.icons.windows

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp
import dev.nucleusframework.nucleus.window.icons.windows.WindowsControlButtonIcons

val WindowsControlButtonIcons.CloseInactiveDark: ImageVector
    get() {
        if (_CloseInactiveDark != null) {
            return _CloseInactiveDark!!
        }
        _CloseInactiveDark = ImageVector.Builder(
            name = "CloseInactiveDark",
            defaultWidth = 16.dp,
            defaultHeight = 16.dp,
            viewportWidth = 16f,
            viewportHeight = 16f
        ).apply {
            path(fill = SolidColor(Color(0xFF6F737A))) {
                moveTo(3.758f, 3.05f)
                lineToRelative(9.192f, 9.192f)
                lineToRelative(-0.707f, 0.707f)
                lineToRelative(-9.192f, -9.192f)
                close()
            }
            path(fill = SolidColor(Color(0xFF6F737A))) {
                moveTo(12.243f, 3.05f)
                lineToRelative(-9.192f, 9.192f)
                lineToRelative(0.707f, 0.707f)
                lineToRelative(9.192f, -9.192f)
                close()
            }
        }.build()

        return _CloseInactiveDark!!
    }

@Suppress("ObjectPropertyName")
private var _CloseInactiveDark: ImageVector? = null
