package dev.nucleusframework.window.material

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.luminance
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

private const val INACTIVE_BORDER_ALPHA = 0.5f
private const val DARK_LUMINANCE_THRESHOLD = 0.5f

private val isKde =
    Platform.Current == Platform.Linux && LinuxDesktopEnvironment.Current == LinuxDesktopEnvironment.KDE

@Composable
public fun rememberMaterialWindowStyle(colorScheme: ColorScheme): DecoratedWindowStyle =
    remember(colorScheme.background, colorScheme.outlineVariant) {
        DecoratedWindowStyle(
            colors =
                DecoratedWindowColors(
                    background = colorScheme.background,
                    border = colorScheme.outlineVariant,
                    borderInactive = colorScheme.outlineVariant.copy(alpha = INACTIVE_BORDER_ALPHA),
                ),
            metrics = DecoratedWindowMetrics(borderWidth = 1.dp),
        )
    }

@Composable
public fun rememberMaterialTitleBarStyle(colorScheme: ColorScheme): TitleBarStyle =
    remember(
        colorScheme.surface,
        colorScheme.onSurface,
        colorScheme.outlineVariant,
    ) {
        TitleBarStyle(
            colors =
                TitleBarColors(
                    background = colorScheme.surface,
                    inactiveBackground = colorScheme.surface,
                    content = colorScheme.onSurface,
                    border = colorScheme.outlineVariant,
                    fullscreenControlButtonsBackground = colorScheme.surface,
                ),
            metrics =
                TitleBarMetrics(
                    height = 40.dp,
                    titlePaneButtonSize = if (isKde) DpSize(28.dp, 28.dp) else DpSize(40.dp, 40.dp),
                ),
        )
    }

internal fun ColorScheme.isDark(): Boolean = background.luminance() < DARK_LUMINANCE_THRESHOLD
