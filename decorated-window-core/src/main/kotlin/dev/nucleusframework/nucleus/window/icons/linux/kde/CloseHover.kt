package dev.nucleusframework.nucleus.window.icons.linux.kde

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp
import dev.nucleusframework.nucleus.window.icons.linux.kde.KdeControlButtonsIcons

val KdeControlButtonsIcons.CloseHover: ImageVector
    get() {
        if (_CloseHover != null) {
            return _CloseHover!!
        }
        _CloseHover = ImageVector.Builder(
            name = "CloseHover",
            defaultWidth = 20.dp,
            defaultHeight = 20.dp,
            viewportWidth = 20f,
            viewportHeight = 20f
        ).apply {
            path(fill = SolidColor(Color(0xFFED1515))) {
                moveTo(10f, 10f)
                moveToRelative(-9f, 0f)
                arcToRelative(9f, 9f, 0f, isMoreThanHalf = true, isPositiveArc = true, 18f, 0f)
                arcToRelative(9f, 9f, 0f, isMoreThanHalf = true, isPositiveArc = true, -18f, 0f)
            }
            path(
                stroke = SolidColor(Color.White),
                strokeLineWidth = 1f,
                strokeLineCap = StrokeCap.Round
            ) {
                moveTo(14f, 14f)
                lineTo(6f, 6f)
                moveTo(14f, 6f)
                lineTo(6f, 14f)
            }
        }.build()

        return _CloseHover!!
    }

@Suppress("ObjectPropertyName")
private var _CloseHover: ImageVector? = null
