package dev.nucleusframework.nucleus.window.icons.windows

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp
import dev.nucleusframework.nucleus.window.icons.windows.WindowsControlButtonIcons

val WindowsControlButtonIcons.CloseFullscreenInactive: ImageVector
    get() {
        if (_CloseFullscreenInactive != null) {
            return _CloseFullscreenInactive!!
        }
        _CloseFullscreenInactive = ImageVector.Builder(
            name = "CloseFullscreenInactive",
            defaultWidth = 16.dp,
            defaultHeight = 16.dp,
            viewportWidth = 16f,
            viewportHeight = 16f
        ).apply {
            path(
                stroke = SolidColor(Color(0xFF818594)),
                strokeLineWidth = 1f
            ) {
                moveTo(4f, 9f)
                horizontalLineTo(7f)
                verticalLineTo(12f)
            }
            path(
                stroke = SolidColor(Color(0xFF818594)),
                strokeLineWidth = 1f
            ) {
                moveTo(3f, 13f)
                lineTo(7f, 9f)
            }
            path(
                stroke = SolidColor(Color(0xFF818594)),
                strokeLineWidth = 1f
            ) {
                moveTo(12f, 7f)
                horizontalLineTo(9f)
                verticalLineTo(4f)
            }
            path(
                stroke = SolidColor(Color(0xFF818594)),
                strokeLineWidth = 1f
            ) {
                moveTo(13f, 3f)
                lineTo(9f, 7f)
            }
        }.build()

        return _CloseFullscreenInactive!!
    }

@Suppress("ObjectPropertyName")
private var _CloseFullscreenInactive: ImageVector? = null
