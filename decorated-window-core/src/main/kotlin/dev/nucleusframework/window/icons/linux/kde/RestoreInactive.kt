package dev.nucleusframework.window.icons.linux.kde

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp
import dev.nucleusframework.window.icons.linux.kde.KdeControlButtonsIcons

public val KdeControlButtonsIcons.RestoreInactive: ImageVector
    get() {
        if (_RestoreInactive != null) {
            return _RestoreInactive!!
        }
        _RestoreInactive = ImageVector.Builder(
            name = "RestoreInactive",
            defaultWidth = 20.dp,
            defaultHeight = 20.dp,
            viewportWidth = 20f,
            viewportHeight = 20f
        ).apply {
            path(
                stroke = SolidColor(Color(0xFFA0A4AA)),
                strokeLineWidth = 1f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(14.5f, 10f)
                lineTo(10f, 5.5f)
                lineTo(5.5f, 10f)
                lineTo(10f, 14.5f)
                lineTo(14.5f, 10f)
                close()
            }
        }.build()

        return _RestoreInactive!!
    }

@Suppress("ObjectPropertyName")
private var _RestoreInactive: ImageVector? = null
