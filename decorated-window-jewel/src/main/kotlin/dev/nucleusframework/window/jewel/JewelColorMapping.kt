package dev.nucleusframework.window.jewel

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import dev.nucleusframework.core.runtime.LinuxDesktopEnvironment
import dev.nucleusframework.core.runtime.Platform
import dev.nucleusframework.window.styling.DecoratedWindowColors
import dev.nucleusframework.window.styling.DecoratedWindowMetrics
import dev.nucleusframework.window.styling.DecoratedWindowStyle
import dev.nucleusframework.window.styling.TitleBarColors
import dev.nucleusframework.window.styling.TitleBarMetrics
import dev.nucleusframework.window.styling.TitleBarStyle
import org.jetbrains.jewel.foundation.theme.JewelTheme

private const val INACTIVE_BORDER_ALPHA = 0.5f

private val isLinux = Platform.Current == Platform.Linux

private val isKde =
    isLinux && LinuxDesktopEnvironment.Current == LinuxDesktopEnvironment.KDE

@Composable
public fun rememberJewelWindowStyle(): DecoratedWindowStyle {
    val isDark = JewelTheme.isDark
    val background = JewelTheme.globalColors.panelBackground
    val borderColor =
        if (isLinux && isDark) {
            JewelTheme.globalColors.borders.normal
                .copy(alpha = 0.6f)
        } else {
            JewelTheme.globalColors.borders.normal
        }
    return remember(background, borderColor, isDark) {
        DecoratedWindowStyle(
            colors =
                DecoratedWindowColors(
                    border = borderColor,
                    borderInactive = borderColor.copy(alpha = INACTIVE_BORDER_ALPHA),
                    background = background,
                ),
            metrics = DecoratedWindowMetrics(borderWidth = 1.dp),
        )
    }
}

@Composable
public fun rememberJewelTitleBarStyle(): TitleBarStyle {
    val background = JewelTheme.globalColors.panelBackground
    val contentColor = JewelTheme.contentColor
    val borderColor = JewelTheme.globalColors.borders.normal
    return remember(background, contentColor, borderColor) {
        TitleBarStyle(
            colors =
                TitleBarColors(
                    background = background,
                    inactiveBackground = background,
                    content = contentColor,
                    border = borderColor,
                    fullscreenControlButtonsBackground = background,
                ),
            metrics =
                TitleBarMetrics(
                    height = 40.dp,
                    titlePaneButtonSize = if (isKde) DpSize(28.dp, 28.dp) else DpSize(40.dp, 40.dp),
                ),
        )
    }
}
