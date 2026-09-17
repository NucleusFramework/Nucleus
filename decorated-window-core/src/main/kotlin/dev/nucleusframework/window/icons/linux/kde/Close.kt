package dev.nucleusframework.window.icons.linux.kde

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp
import dev.nucleusframework.window.icons.linux.kde.KdeControlButtonsIcons

public val KdeControlButtonsIcons.Close: ImageVector
    get() {
        if (_Close != null) {
            return _Close!!
        }
        _Close = ImageVector.Builder(
            name = "Close",
            defaultWidth = 20.dp,
            defaultHeight = 20.dp,
            viewportWidth = 20f,
            viewportHeight = 20f
        ).apply {
            path(
                stroke = SolidColor(Color(0xFF232629)),
                strokeLineWidth = 1f,
                strokeLineCap = StrokeCap.Round
            ) {
                moveTo(14f, 14f)
                lineTo(6f, 6f)
                moveTo(14f, 6f)
                lineTo(6f, 14f)
            }
        }.build()

        return _Close!!
    }

@Suppress("ObjectPropertyName")
private var _Close: ImageVector? = null
