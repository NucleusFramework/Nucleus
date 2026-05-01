package io.github.kdroidfilter.nucleus.window.styling

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import io.github.kdroidfilter.nucleus.window.DecoratedWindowState

data class TitleBarStyle(
    val colors: TitleBarColors,
    val metrics: TitleBarMetrics,
)

data class TitleBarColors(
    val background: Color,
    val inactiveBackground: Color,
    val content: Color,
    val border: Color,
    val fullscreenControlButtonsBackground: Color = Color.Unspecified,
    val iconButtonHoveredBackground: Color = Color.Transparent,
    val iconButtonPressedBackground: Color = Color.Transparent,
    val controlButtonIconColor: Color = Color.Unspecified,
    val controlButtonIconHoverColor: Color = Color.Unspecified,
) {
    @Composable
    fun backgroundFor(state: DecoratedWindowState): State<Color> =
        rememberUpdatedState(if (state.isActive) background else inactiveBackground)
}

@Suppress("MagicNumber")
data class TitleBarMetrics(
    val height: Dp = 40.dp,
    val gradientStartX: Dp = (-100).dp,
    val gradientEndX: Dp = 400.dp,
    val titlePaneButtonSize: DpSize = DpSize(40.dp, 40.dp),
)

val LocalTitleBarStyle =
    staticCompositionLocalOf<TitleBarStyle> {
        io.github.kdroidfilter.nucleus.window.DecoratedWindowDefaults.darkTitleBarStyle()
    }
