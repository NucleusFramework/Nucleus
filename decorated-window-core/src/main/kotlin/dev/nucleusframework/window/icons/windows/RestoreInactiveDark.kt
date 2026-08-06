package dev.nucleusframework.window.icons.windows

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp
import dev.nucleusframework.window.icons.windows.WindowsControlButtonIcons

public val WindowsControlButtonIcons.RestoreInactiveDark: ImageVector
    get() {
        if (_RestoreInactiveDark != null) {
            return _RestoreInactiveDark!!
        }
        _RestoreInactiveDark = ImageVector.Builder(
            name = "RestoreInactiveDark",
            defaultWidth = 16.dp,
            defaultHeight = 16.dp,
            viewportWidth = 16f,
            viewportHeight = 16f
        ).apply {
            path(
                fill = SolidColor(Color(0xFF6F737A)),
                pathFillType = PathFillType.EvenOdd
            ) {
                moveTo(5f, 3f)
                horizontalLineTo(13f)
                verticalLineTo(11f)
                horizontalLineTo(10f)
                verticalLineTo(10f)
                horizontalLineTo(12f)
                verticalLineTo(4f)
                horizontalLineTo(6f)
                verticalLineTo(6f)
                horizontalLineTo(5f)
                verticalLineTo(3f)
                close()
            }
            path(
                fill = SolidColor(Color(0xFF6F737A)),
                pathFillType = PathFillType.EvenOdd
            ) {
                moveTo(11f, 5f)
                horizontalLineTo(3f)
                verticalLineTo(13f)
                horizontalLineTo(11f)
                verticalLineTo(5f)
                close()
                moveTo(10f, 6f)
                horizontalLineTo(4f)
                verticalLineTo(12f)
                horizontalLineTo(10f)
                verticalLineTo(6f)
                close()
            }
        }.build()

        return _RestoreInactiveDark!!
    }

@Suppress("ObjectPropertyName")
private var _RestoreInactiveDark: ImageVector? = null
