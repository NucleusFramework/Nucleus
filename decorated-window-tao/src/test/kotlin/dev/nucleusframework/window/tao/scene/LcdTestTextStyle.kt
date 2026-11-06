package dev.nucleusframework.window.tao.scene

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.FontHinting
import androidx.compose.ui.text.FontRasterizationSettings
import androidx.compose.ui.text.FontSmoothing
import androidx.compose.ui.text.PlatformParagraphStyle
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle

/**
 * Test twin of the ClearType default the Nucleus Gradle plugin bakes into
 * `FontRasterizationSettings.PlatformDefault` (`LcdTextDefaultTransform`).
 * Library tests run against the unpatched Compose artifact, so LCD-rendering
 * tests request subpixel rasterization explicitly through this style.
 */
@OptIn(ExperimentalTextApi::class)
internal fun taoLcdTextStyle(): TextStyle =
    TextStyle(
        color = Color.Unspecified,
        platformStyle =
            PlatformTextStyle(
                spanStyle = null,
                paragraphStyle =
                    PlatformParagraphStyle(
                        FontRasterizationSettings(
                            smoothing = FontSmoothing.SubpixelAntiAlias,
                            hinting = FontHinting.Normal,
                            subpixelPositioning = true,
                            autoHintingForced = false,
                        ),
                    ),
            ),
    )
