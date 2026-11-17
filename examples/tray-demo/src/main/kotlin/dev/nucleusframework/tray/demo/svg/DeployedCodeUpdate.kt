package dev.nucleusframework.tray.demo.svg

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

val DeployedCodeUpdate: ImageVector
    get() {
        if (_deployedCodeUpdate != null) return _deployedCodeUpdate!!

        _deployedCodeUpdate =
            ImageVector
                .Builder(
                    name = "DeployedCodeUpdate",
                    defaultWidth = 24.dp,
                    defaultHeight = 24.dp,
                    viewportWidth = 960f,
                    viewportHeight = 960f,
                ).apply {
                    path(
                        fill = SolidColor(Color(0xFF000000)),
                    ) {
                        moveToRelative(720f, -80f)
                        lineToRelative(120f, -120f)
                        lineToRelative(-28f, -28f)
                        lineToRelative(-72f, 72f)
                        verticalLineToRelative(-164f)
                        horizontalLineToRelative(-40f)
                        verticalLineToRelative(164f)
                        lineToRelative(-72f, -72f)
                        lineToRelative(-28f, 28f)
                        close()
                        moveTo(480f, 160f)
                        lineTo(243f, 297f)
                        lineToRelative(237f, 137f)
                        lineToRelative(237f, -137f)
                        close()
                        moveTo(120f, 639f)
                        verticalLineToRelative(-318f)
                        quadToRelative(0f, -22f, 10.5f, -40f)
                        reflectiveQuadToRelative(29.5f, -29f)
                        lineToRelative(280f, -161f)
                        quadToRelative(10f, -5f, 19.5f, -8f)
                        reflectiveQuadToRelative(20.5f, -3f)
                        reflectiveQuadToRelative(21f, 3f)
                        reflectiveQuadToRelative(19f, 8f)
                        lineToRelative(280f, 161f)
                        quadToRelative(19f, 11f, 29.5f, 29f)
                        reflectiveQuadToRelative(10.5f, 40f)
                        verticalLineToRelative(159f)
                        horizontalLineToRelative(-80f)
                        verticalLineToRelative(-116f)
                        lineTo(479f, 526f)
                        lineTo(200f, 364f)
                        verticalLineToRelative(274f)
                        lineToRelative(240f, 139f)
                        verticalLineToRelative(92f)
                        lineTo(160f, 708f)
                        quadToRelative(-19f, -11f, -29.5f, -29f)
                        reflectiveQuadTo(120f, 639f)
                        moveTo(720f, 960f)
                        quadToRelative(-83f, 0f, -141.5f, -58.5f)
                        reflectiveQuadTo(520f, 760f)
                        reflectiveQuadToRelative(58.5f, -141.5f)
                        reflectiveQuadTo(720f, 560f)
                        reflectiveQuadToRelative(141.5f, 58.5f)
                        reflectiveQuadTo(920f, 760f)
                        reflectiveQuadTo(861.5f, 901.5f)
                        reflectiveQuadTo(720f, 960f)
                        moveTo(480f, 469f)
                    }
                }.build()

        return _deployedCodeUpdate!!
    }

private var _deployedCodeUpdate: ImageVector? = null
