package dev.nucleusframework.window.styling

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.State
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import dev.nucleusframework.window.DecoratedWindowState

public data class TitleBarStyle(
    val colors: TitleBarColors,
    val metrics: TitleBarMetrics,
)

public data class TitleBarColors(
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
    public fun backgroundFor(state: DecoratedWindowState): State<Color> =
        rememberUpdatedState(if (state.isActive) background else inactiveBackground)
}

@Suppress("MagicNumber")
public data class TitleBarMetrics(
    val height: Dp = 40.dp,
    val gradientStartX: Dp = (-100).dp,
    val gradientEndX: Dp = 400.dp,
    val titlePaneButtonSize: DpSize = DpSize(40.dp, 40.dp),
)

public val LocalTitleBarStyle: ProvidableCompositionLocal<TitleBarStyle> =
    staticCompositionLocalOf<TitleBarStyle> {
        dev.nucleusframework.window.DecoratedWindowDefaults
            .darkTitleBarStyle()
    }
