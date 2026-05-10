package dev.nucleusframework.nucleus.window.icons.linux.kde

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp
import dev.nucleusframework.nucleus.window.icons.linux.kde.KdeControlButtonsIcons

val KdeControlButtonsIcons.CloseInactive: ImageVector
    get() {
        if (_CloseInactive != null) {
            return _CloseInactive!!
        }
        _CloseInactive = ImageVector.Builder(
            name = "CloseInactive",
            defaultWidth = 20.dp,
            defaultHeight = 20.dp,
            viewportWidth = 20f,
            viewportHeight = 20f
        ).apply {
            path(
                stroke = SolidColor(Color(0xFFA0A4AA)),
                strokeLineWidth = 1f,
                strokeLineCap = StrokeCap.Round
            ) {
                moveTo(14f, 14f)
                lineTo(6f, 6f)
                moveTo(14f, 6f)
                lineTo(6f, 14f)
            }
        }.build()

        return _CloseInactive!!
    }

@Suppress("ObjectPropertyName")
private var _CloseInactive: ImageVector? = null
