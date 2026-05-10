package dev.nucleusframework.nucleus.window.icons.windows

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp
import dev.nucleusframework.nucleus.window.icons.windows.WindowsControlButtonIcons

val WindowsControlButtonIcons.CloseHover: ImageVector
    get() {
        if (_CloseHover != null) {
            return _CloseHover!!
        }
        _CloseHover = ImageVector.Builder(
            name = "CloseHover",
            defaultWidth = 16.dp,
            defaultHeight = 16.dp,
            viewportWidth = 16f,
            viewportHeight = 16f
        ).apply {
            path(
                fill = SolidColor(Color.White),
                pathFillType = PathFillType.EvenOdd
            ) {
                moveTo(3.757f, 3.05f)
                lineToRelative(9.192f, 9.192f)
                lineToRelative(-0.707f, 0.707f)
                lineToRelative(-9.192f, -9.192f)
                close()
            }
            path(
                fill = SolidColor(Color.White),
                pathFillType = PathFillType.EvenOdd
            ) {
                moveTo(12.243f, 3.05f)
                lineToRelative(-9.192f, 9.192f)
                lineToRelative(0.707f, 0.707f)
                lineToRelative(9.192f, -9.192f)
                close()
            }
        }.build()

        return _CloseHover!!
    }

@Suppress("ObjectPropertyName")
private var _CloseHover: ImageVector? = null
