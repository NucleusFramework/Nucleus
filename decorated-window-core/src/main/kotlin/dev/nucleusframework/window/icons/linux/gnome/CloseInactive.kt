package dev.nucleusframework.window.icons.linux.gnome

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp
import dev.nucleusframework.window.icons.linux.gnome.GnomeControlButtonsIcons

public val GnomeControlButtonsIcons.CloseInactive: ImageVector
    get() {
        if (_CloseInactive != null) {
            return _CloseInactive!!
        }
        _CloseInactive = ImageVector.Builder(
            name = "CloseInactive",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            path(
                stroke = SolidColor(Color(0xFF6C707E)),
                strokeLineWidth = 1f,
                strokeLineCap = StrokeCap.Round
            ) {
                moveTo(15.5f, 15.5f)
                lineTo(8.5f, 8.5f)
                moveTo(15.5f, 8.5f)
                lineTo(8.5f, 15.5f)
            }
        }.build()

        return _CloseInactive!!
    }

@Suppress("ObjectPropertyName")
private var _CloseInactive: ImageVector? = null
