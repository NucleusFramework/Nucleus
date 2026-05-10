package io.github.kdroidfilter.nucleus.window.material

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import io.github.kdroidfilter.nucleus.core.runtime.LinuxDesktopEnvironment
import io.github.kdroidfilter.nucleus.core.runtime.Platform
import io.github.kdroidfilter.nucleus.window.styling.DecoratedWindowColors
import io.github.kdroidfilter.nucleus.window.styling.DecoratedWindowMetrics
import io.github.kdroidfilter.nucleus.window.styling.DecoratedWindowStyle
import io.github.kdroidfilter.nucleus.window.styling.TitleBarColors
import io.github.kdroidfilter.nucleus.window.styling.TitleBarMetrics
import io.github.kdroidfilter.nucleus.window.styling.TitleBarStyle

private const val INACTIVE_BORDER_ALPHA = 0.5f
private const val DARK_LUMINANCE_THRESHOLD = 0.5f

private val isKde =
    Platform.Current == Platform.Linux && LinuxDesktopEnvironment.Current == LinuxDesktopEnvironment.KDE

@Composable
fun rememberMaterialWindowStyle(colorScheme: ColorScheme): DecoratedWindowStyle =
    remember(colorScheme.outlineVariant) {
        DecoratedWindowStyle(
            colors =
                DecoratedWindowColors(
                    border = colorScheme.outlineVariant,
                    borderInactive = colorScheme.outlineVariant.copy(alpha = INACTIVE_BORDER_ALPHA),
                ),
            metrics = DecoratedWindowMetrics(borderWidth = 1.dp),
        )
    }

@Composable
fun rememberMaterialTitleBarStyle(colorScheme: ColorScheme): TitleBarStyle =
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
