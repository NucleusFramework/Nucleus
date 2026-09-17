package dev.nucleusframework.window.icons.windows

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp
import dev.nucleusframework.window.icons.windows.WindowsControlButtonIcons

public val WindowsControlButtonIcons.CloseFullscreenDark: ImageVector
    get() {
        if (_CloseFullscreenDark != null) {
            return _CloseFullscreenDark!!
        }
        _CloseFullscreenDark = ImageVector.Builder(
            name = "CloseFullscreenDark",
            defaultWidth = 16.dp,
            defaultHeight = 16.dp,
            viewportWidth = 16f,
            viewportHeight = 16f
        ).apply {
            path(
                stroke = SolidColor(Color(0xFFCED0D6)),
                strokeLineWidth = 1f
            ) {
                moveTo(4f, 9f)
                horizontalLineTo(7f)
                verticalLineTo(12f)
            }
            path(
                stroke = SolidColor(Color(0xFFCED0D6)),
                strokeLineWidth = 1f
            ) {
                moveTo(3f, 13f)
                lineTo(7f, 9f)
            }
            path(
                stroke = SolidColor(Color(0xFFCED0D6)),
                strokeLineWidth = 1f
            ) {
                moveTo(12f, 7f)
                horizontalLineTo(9f)
                verticalLineTo(4f)
            }
            path(
                stroke = SolidColor(Color(0xFFCED0D6)),
                strokeLineWidth = 1f
            ) {
                moveTo(13f, 3f)
                lineTo(9f, 7f)
            }
        }.build()

        return _CloseFullscreenDark!!
    }

@Suppress("ObjectPropertyName")
private var _CloseFullscreenDark: ImageVector? = null
