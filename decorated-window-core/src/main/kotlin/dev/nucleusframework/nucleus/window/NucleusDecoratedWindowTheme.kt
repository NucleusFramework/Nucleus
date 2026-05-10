package dev.nucleusframework.nucleus.window

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import dev.nucleusframework.nucleus.core.runtime.LinuxDesktopEnvironment
import dev.nucleusframework.nucleus.core.runtime.Platform
import dev.nucleusframework.nucleus.window.styling.DecoratedWindowColors
import dev.nucleusframework.nucleus.window.styling.DecoratedWindowMetrics
import dev.nucleusframework.nucleus.window.styling.DecoratedWindowStyle
import dev.nucleusframework.nucleus.window.styling.LocalDecoratedWindowStyle
import dev.nucleusframework.nucleus.window.styling.LocalTitleBarStyle
import dev.nucleusframework.nucleus.window.styling.TitleBarColors
import dev.nucleusframework.nucleus.window.styling.TitleBarMetrics
import dev.nucleusframework.nucleus.window.styling.TitleBarStyle

val LocalIsDarkTheme = androidx.compose.runtime.staticCompositionLocalOf { true }

@Suppress("FunctionNaming")
@Composable
fun NucleusDecoratedWindowTheme(
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
object DecoratedWindowDefaults {
    private val isKde =
        Platform.Current == Platform.Linux && LinuxDesktopEnvironment.Current == LinuxDesktopEnvironment.KDE

    fun lightWindowStyle(): DecoratedWindowStyle =
        DecoratedWindowStyle(
            colors =
                DecoratedWindowColors(
                    border = Color(0x12FFFFFF),
                    borderInactive = Color(0x12FFFFFF),
                ),
            metrics = DecoratedWindowMetrics(borderWidth = 1.dp),
        )

    fun darkWindowStyle(): DecoratedWindowStyle =
        DecoratedWindowStyle(
            colors =
                DecoratedWindowColors(
                    border = Color(0x12FFFFFF),
                    borderInactive = Color(0x12FFFFFF),
                ),
            metrics = DecoratedWindowMetrics(borderWidth = 1.dp),
        )

    fun lightTitleBarStyle(): TitleBarStyle =
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

    fun darkTitleBarStyle(): TitleBarStyle =
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
