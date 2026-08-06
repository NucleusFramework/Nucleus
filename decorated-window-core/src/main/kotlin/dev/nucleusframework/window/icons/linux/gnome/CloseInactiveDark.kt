package dev.nucleusframework.window.icons.linux.gnome

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp
import dev.nucleusframework.window.icons.linux.gnome.GnomeControlButtonsIcons

public val GnomeControlButtonsIcons.CloseInactiveDark: ImageVector
    get() {
        if (_CloseInactiveDark != null) {
            return _CloseInactiveDark!!
        }
        _CloseInactiveDark = ImageVector.Builder(
            name = "CloseInactiveDark",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            path(
                stroke = SolidColor(Color(0xFFCED0D6)),
                strokeLineWidth = 1f,
                strokeLineCap = StrokeCap.Round
            ) {
                moveTo(15.5f, 15.5f)
                lineTo(8.5f, 8.5f)
                moveTo(15.5f, 8.5f)
                lineTo(8.5f, 15.5f)
            }
        }.build()

        return _CloseInactiveDark!!
    }

@Suppress("ObjectPropertyName")
private var _CloseInactiveDark: ImageVector? = null
