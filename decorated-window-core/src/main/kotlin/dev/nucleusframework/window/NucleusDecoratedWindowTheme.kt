package dev.nucleusframework.window

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import dev.nucleusframework.core.runtime.LinuxDesktopEnvironment
import dev.nucleusframework.core.runtime.Platform
import dev.nucleusframework.window.styling.DecoratedWindowColors
import dev.nucleusframework.window.styling.DecoratedWindowMetrics
import dev.nucleusframework.window.styling.DecoratedWindowStyle
import dev.nucleusframework.window.styling.LocalDecoratedWindowStyle
import dev.nucleusframework.window.styling.LocalTitleBarStyle
import dev.nucleusframework.window.styling.TitleBarColors
import dev.nucleusframework.window.styling.TitleBarMetrics
import dev.nucleusframework.window.styling.TitleBarStyle

public val LocalIsDarkTheme: androidx.compose.runtime.ProvidableCompositionLocal<Boolean> =
    androidx.compose.runtime.staticCompositionLocalOf { true }

@Suppress("FunctionNaming")
@Composable
public fun NucleusDecoratedWindowTheme(
    isDark: Boolean = true,
    windowStyle: DecoratedWindowStyle =
        if (isDark) {
            DecoratedWindowDefaults.darkWindowStyle()
        } else {
            DecoratedWindowDefaults.lightWindowStyle()
        },
    titleBarStyle: TitleBarStyle =
        if (isDark) {
            DecoratedWindowDefaults.darkTitleBarStyle()
        } else {
            DecoratedWindowDefaults.lightTitleBarStyle()
        },
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(
        LocalIsDarkTheme provides isDark,
        LocalDecoratedWindowStyle provides windowStyle,
        LocalTitleBarStyle provides titleBarStyle,
    ) {
        content()
    }
}

@Suppress("MagicNumber")
public object DecoratedWindowDefaults {
    private val isKde =
        Platform.Current == Platform.Linux && LinuxDesktopEnvironment.Current == LinuxDesktopEnvironment.KDE

    public fun lightWindowStyle(): DecoratedWindowStyle =
        DecoratedWindowStyle(
            colors =
                DecoratedWindowColors(
                    border = Color(0x0F000000),
                    borderInactive = Color(0x0F000000),
                    background = Color.White,
                ),
            metrics = DecoratedWindowMetrics(borderWidth = 1.dp),
        )

    public fun darkWindowStyle(): DecoratedWindowStyle =
        DecoratedWindowStyle(
            colors =
                DecoratedWindowColors(
                    border = Color(0x12FFFFFF),
                    borderInactive = Color(0x12FFFFFF),
                    background = Color(0xFF2B2B2B),
                ),
            metrics = DecoratedWindowMetrics(borderWidth = 1.dp),
        )

    public fun lightTitleBarStyle(): TitleBarStyle =
        TitleBarStyle(
            colors =
                if (isKde) {
                    TitleBarColors(
                        background = Color(0xFFE3E5E7),
                        inactiveBackground = Color(0xFFEFF0F1),
                        content = Color(0xFF232629),
                        border = Color(0xFFBDBFC1),
                        fullscreenControlButtonsBackground = Color(0xFFE3E5E7),
                    )
                } else {
                    TitleBarColors(
                        background = Color(0xFFF0F0F0),
                        inactiveBackground = Color(0xFFFAFAFA),
                        content = Color(0xFF1E1E1E),
                        border = Color(0x1F000006),
                        fullscreenControlButtonsBackground = Color(0xFFF0F0F0),
                    )
                },
            metrics =
                TitleBarMetrics(
                    height = 40.dp,
                    titlePaneButtonSize = if (isKde) DpSize(28.dp, 28.dp) else DpSize(40.dp, 40.dp),
                ),
        )

    public fun darkTitleBarStyle(): TitleBarStyle =
        TitleBarStyle(
            colors =
                if (isKde) {
                    TitleBarColors(
                        background = Color(0xFF272C31),
                        inactiveBackground = Color(0xFF202428),
                        content = Color(0xFFFCFCFC),
                        border = Color(0xFF52565A),
                        fullscreenControlButtonsBackground = Color(0xFF272C31),
                    )
                } else {
                    TitleBarColors(
                        background = Color(0xFF3C3C3C),
                        inactiveBackground = Color(0xFF2B2B2B),
                        content = Color(0xFFE0E0E0),
                        border = Color(0x5C000006),
                        fullscreenControlButtonsBackground = Color(0xFF3C3C3C),
                    )
                },
            metrics =
                TitleBarMetrics(
                    height = 40.dp,
                    titlePaneButtonSize = if (isKde) DpSize(28.dp, 28.dp) else DpSize(40.dp, 40.dp),
                ),
        )
}
