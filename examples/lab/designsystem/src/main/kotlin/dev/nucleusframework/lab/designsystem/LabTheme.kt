package dev.nucleusframework.lab.designsystem

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import dev.nucleusframework.window.NucleusDecoratedWindowTheme
import dev.nucleusframework.window.jewel.rememberJewelTitleBarStyle
import dev.nucleusframework.window.jewel.rememberJewelWindowStyle
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.intui.standalone.theme.IntUiTheme
import org.jetbrains.jewel.intui.standalone.theme.createDefaultTextStyle
import org.jetbrains.jewel.intui.standalone.theme.createEditorTextStyle
import org.jetbrains.jewel.intui.standalone.theme.darkThemeDefinition
import org.jetbrains.jewel.intui.standalone.theme.default
import org.jetbrains.jewel.intui.standalone.theme.lightThemeDefinition
import org.jetbrains.jewel.ui.ComponentStyling

/** Signal colours that must read the same in light and dark: pass, fail, warning. */
@Immutable
data class LabSignals(
    val ok: Color,
    val warning: Color,
    val error: Color,
    val muted: Color,
)

val LocalLabSignals = staticCompositionLocalOf { LabSignals(Color.Green, Color.Yellow, Color.Red, Color.Gray) }

/**
 * The Lab theme: IntelliJ's Int UI (Jewel standalone), light or dark, plus the Jewel
 * decorated-window styling, so every window opened below it (shell, sessions, `TabWindows`,
 * satellites) wears the same chrome.
 *
 * Jewel's component styles are built from its fixed Int UI palette, so the OS accent colour
 * cannot be applied to them cleanly: the Lab keeps Jewel's blue.
 */
@Composable
fun LabTheme(
    isDark: Boolean,
    content: @Composable () -> Unit,
) {
    val textStyle = JewelTheme.createDefaultTextStyle()
    val editorStyle = JewelTheme.createEditorTextStyle()
    val definition =
        remember(isDark, textStyle, editorStyle) {
            if (isDark) {
                JewelTheme.darkThemeDefinition(defaultTextStyle = textStyle, editorTextStyle = editorStyle)
            } else {
                JewelTheme.lightThemeDefinition(defaultTextStyle = textStyle, editorTextStyle = editorStyle)
            }
        }
    IntUiTheme(theme = definition, styling = ComponentStyling.default()) {
        val colors = jewelLabColors()
        CompositionLocalProvider(
            LocalLabColors provides colors,
            LocalLabTypography provides jewelLabTypography(),
            LocalLabSignals provides LabSignals(colors.ok, colors.warning, colors.error, colors.textMuted),
        ) {
            NucleusDecoratedWindowTheme(
                isDark = isDark,
                windowStyle = rememberJewelWindowStyle(),
                titleBarStyle = rememberJewelTitleBarStyle(),
                content = content,
            )
        }
    }
}

/** Theme-agnostic access to the Lab tokens; probes use these, never a Material or Jewel scheme. */
object LabTheme {
    val colors: LabColors
        @Composable @ReadOnlyComposable
        get() = LocalLabColors.current

    val typography: LabTypography
        @Composable @ReadOnlyComposable
        get() = LocalLabTypography.current

    val isDark: Boolean
        @Composable @ReadOnlyComposable
        get() = LocalLabColors.current.isDark
}
