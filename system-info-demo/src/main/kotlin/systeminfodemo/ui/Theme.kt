@file:Suppress("MagicNumber")

package systeminfodemo.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import dev.nucleusframework.nucleus.darkmodedetector.isSystemInDarkMode
import dev.nucleusframework.nucleus.systemcolor.systemAccentColor
import org.jetbrains.jewel.foundation.BorderColors
import org.jetbrains.jewel.foundation.GlobalColors
import org.jetbrains.jewel.foundation.OutlineColors
import org.jetbrains.jewel.foundation.TextColors
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.foundation.theme.ThemeDefinition
import org.jetbrains.jewel.intui.standalone.theme.dark
import org.jetbrains.jewel.intui.standalone.theme.darkThemeDefinition
import org.jetbrains.jewel.intui.standalone.theme.default
import org.jetbrains.jewel.intui.standalone.theme.light
import org.jetbrains.jewel.intui.standalone.theme.lightThemeDefinition
import org.jetbrains.jewel.ui.ComponentStyling

private val defaultAccentDark = Color(0xFF3574F0)
private val defaultAccentLight = Color(0xFF4682FA)

@Composable
fun resolveAccentColor(isDark: Boolean): Color {
    val systemColor = systemAccentColor()
    return systemColor ?: if (isDark) defaultAccentDark else defaultAccentLight
}

@Composable
fun buildIslandsTheme(): Pair<ThemeDefinition, ComponentStyling> {
    val isDark = isSystemInDarkMode()
    val accent = resolveAccentColor(isDark)

    val theme =
        if (isDark) {
            JewelTheme.darkThemeDefinition(
                colors = islandsDarkColors(accent),
            )
        } else {
            JewelTheme.lightThemeDefinition(
                colors = islandsLightColors(accent),
            )
        }

    return theme to ComponentStyling.default()
}

private fun islandsDarkColors(accent: Color): GlobalColors =
    GlobalColors.dark(
        borders =
            BorderColors.dark(
                normal = Color(0xFF3C3F41),
                focused = accent,
                disabled = Color(0xFF2B2D30),
            ),
        outlines =
            OutlineColors.dark(
                focused = accent,
                focusedWarning = Color(0xFFE8A33E),
                focusedError = Color(0xFFF75464),
                warning = Color(0xFFE8A33E),
                error = Color(0xFFF75464),
            ),
        text =
            TextColors.dark(
                normal = Color(0xFFBCBEC4),
                selected = Color(0xFFBCBEC4),
                disabled = Color(0xFF7A7E85),
                info = Color(0xFF7A7E85),
                error = Color(0xFFF75464),
            ),
        panelBackground = Color(0xFF1E1F22),
        toolwindowBackground = Color(0xFF181A1D),
    )

private fun islandsLightColors(accent: Color): GlobalColors =
    GlobalColors.light(
        outlines =
            OutlineColors.light(
                focused = accent,
                focusedWarning = Color(0xFFE8A33E),
                focusedError = Color(0xFFF75464),
                warning = Color(0xFFE8A33E),
                error = Color(0xFFF75464),
            ),
        borders =
            BorderColors.light(
                focused = accent,
            ),
        toolwindowBackground = Color(0xFFE8E9EB),
    )

// Title bar gradient: blend accent into panel background
@Composable
fun titleBarGradientColor(): Color {
    val accent = JewelTheme.globalColors.outlines.focused
    val bg = JewelTheme.globalColors.panelBackground
    val t = 0.25f
    return Color(
        red = bg.red * (1f - t) + accent.red * t,
        green = bg.green * (1f - t) + accent.green * t,
        blue = bg.blue * (1f - t) + accent.blue * t,
        alpha = 1f,
    )
}
