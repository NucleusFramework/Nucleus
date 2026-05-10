package dev.nucleusframework.nucleus.window.material2

import androidx.compose.material.Colors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import dev.nucleusframework.nucleus.core.runtime.LinuxDesktopEnvironment
import dev.nucleusframework.nucleus.core.runtime.Platform
import dev.nucleusframework.nucleus.window.styling.DecoratedWindowColors
import dev.nucleusframework.nucleus.window.styling.DecoratedWindowMetrics
import dev.nucleusframework.nucleus.window.styling.DecoratedWindowStyle
import dev.nucleusframework.nucleus.window.styling.TitleBarColors
import dev.nucleusframework.nucleus.window.styling.TitleBarMetrics
import dev.nucleusframework.nucleus.window.styling.TitleBarStyle

private const val BORDER_ALPHA = 0.12f
private const val INACTIVE_BORDER_ALPHA = 0.06f

private val isKde =
    Platform.Current == Platform.Linux && LinuxDesktopEnvironment.Current == LinuxDesktopEnvironment.KDE

@Composable
internal fun rememberMaterialWindowStyle(colors: Colors): DecoratedWindowStyle {
    val borderColor = colors.onSurface.copy(alpha = BORDER_ALPHA)
    val inactiveBorderColor = colors.onSurface.copy(alpha = INACTIVE_BORDER_ALPHA)
    return remember(borderColor, inactiveBorderColor) {
        DecoratedWindowStyle(
            colors =
                DecoratedWindowColors(
                    border = borderColor,
                    borderInactive = inactiveBorderColor,
                ),
            metrics = DecoratedWindowMetrics(borderWidth = 1.dp),
        )
    }
}

@Composable
internal fun rememberMaterialTitleBarStyle(colors: Colors): TitleBarStyle {
    val borderColor = colors.onSurface.copy(alpha = BORDER_ALPHA)
    return remember(
        colors.surface,
        colors.onSurface,
        borderColor,
    ) {
        TitleBarStyle(
            colors =
                TitleBarColors(
                    background = colors.surface,
                    inactiveBackground = colors.surface,
                    content = colors.onSurface,
                    border = borderColor,
                    fullscreenControlButtonsBackground = colors.surface,
                ),
            metrics =
                TitleBarMetrics(
                    height = 40.dp,
                    titlePaneButtonSize = if (isKde) DpSize(28.dp, 28.dp) else DpSize(40.dp, 40.dp),
                ),
        )
    }
}
