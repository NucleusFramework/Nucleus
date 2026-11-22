package dev.nucleusframework.tray.demo.svg

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

val AcademicCap: ImageVector
    get() {
        if (_AcademicCap != null) return _AcademicCap!!

        _AcademicCap =
            ImageVector
                .Builder(
                    name = "AcademicCap",
                    defaultWidth = 24.dp,
                    defaultHeight = 24.dp,
                    viewportWidth = 24f,
                    viewportHeight = 24f,
                ).apply {
                    path(
                        stroke = SolidColor(Color(0xFF0F172A)),
                        strokeLineWidth = 1.5f,
                        strokeLineCap = StrokeCap.Round,
                        strokeLineJoin = StrokeJoin.Round,
                    ) {
                        moveTo(4.25933f, 10.1466f)
                        curveTo(3.98688f, 12.2307f, 3.82139f, 14.3483f, 3.76853f, 16.494f)
                        curveTo(6.66451f, 17.703f, 9.41893f, 19.1835f, 12f, 20.9036f)
                        curveTo(14.5811f, 19.1835f, 17.3355f, 17.703f, 20.2315f, 16.494f)
                        curveTo(20.1786f, 14.3484f, 20.0131f, 12.2307f, 19.7407f, 10.1467f)
                        moveTo(4.25933f, 10.1466f)
                        curveTo(3.38362f, 9.8523f, 2.49729f, 9.58107f, 1.60107f, 9.3337f)
                        curveTo(4.84646f, 7.05887f, 8.32741f, 5.0972f, 12f, 3.49255f)
                        curveTo(15.6727f, 5.0972f, 19.1536f, 7.05888f, 22.399f, 9.33371f)
                        curveTo(21.5028f, 9.58109f, 20.6164f, 9.85233f, 19.7407f, 10.1467f)
                        moveTo(4.25933f, 10.1466f)
                        curveTo(6.94656f, 11.0499f, 9.5338f, 12.1709f, 12.0001f, 13.4886f)
                        curveTo(14.4663f, 12.1709f, 17.0535f, 11.0499f, 19.7407f, 10.1467f)
                        moveTo(6.75f, 15f)
                        curveTo(7.16421f, 15f, 7.5f, 14.6642f, 7.5f, 14.25f)
                        curveTo(7.5f, 13.8358f, 7.16421f, 13.5f, 6.75f, 13.5f)
                        curveTo(6.33579f, 13.5f, 6f, 13.8358f, 6f, 14.25f)
                        curveTo(6f, 14.6642f, 6.33579f, 15f, 6.75f, 15f)
                        close()
                        moveTo(6.75f, 15f)
                        verticalLineTo(11.3245f)
                        curveTo(8.44147f, 10.2735f, 10.1936f, 9.31094f, 12f, 8.44329f)
                        moveTo(4.99264f, 19.9926f)
                        curveTo(6.16421f, 18.8211f, 6.75f, 17.2855f, 6.75f, 15.75f)
                        verticalLineTo(14.25f)
                    }
                }.build()

        return _AcademicCap!!
    }

private var _AcademicCap: ImageVector? = null
