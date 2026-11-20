package dev.nucleusframework.lab.designsystem

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.copyWithSize
import org.jetbrains.jewel.ui.theme.colorPalette

/**
 * Every colour the Lab draws with, whatever the design system underneath. Probes read these
 * (`LabTheme.colors`) instead of a Material or Jewel scheme.
 */
@Immutable
data class LabColors(
    /** Behind a probe's content (IntelliJ's editor background). */
    val background: Color,
    /** Chrome: sidebar, panels, title bar (IntelliJ's tool window background). */
    val panel: Color,
    /** Raised above its surroundings: chips, hovered rows, pressed buttons. */
    val raised: Color,
    val border: Color,
    val borderStrong: Color,
    val text: Color,
    val textMuted: Color,
    val textDisabled: Color,
    val accent: Color,
    val onAccent: Color,
    /** Background of a selected row or chip. */
    val selection: Color,
    val ok: Color,
    val warning: Color,
    val error: Color,
    /** Code, output, JSON. */
    val code: Color,
    /** Behind a specimen: neutral, slightly off the page so its bounds are visible. */
    val specimen: Color,
    /** A target area the user acts on. */
    val target: Color,
    /** A pill drawn over native content (video, WebView, SwiftUI, textures). */
    val overlay: Color,
    val onOverlay: Color,
    val isDark: Boolean,
)

/** The Lab's text styles: dense, IntelliJ-sized. */
@Immutable
data class LabTypography(
    /** Page title (probe header). */
    val title: TextStyle,
    /** Section titles, group names. */
    val heading: TextStyle,
    /** Labels of rows, chips and buttons. */
    val label: TextStyle,
    val body: TextStyle,
    /** Hints, secondary lines. */
    val small: TextStyle,
    /** Every value read back from the OS. */
    val mono: TextStyle,
)

internal val LocalLabColors = staticCompositionLocalOf<LabColors> { error("LabColors read outside LabTheme") }
internal val LocalLabTypography =
    staticCompositionLocalOf<LabTypography> { error("LabTypography read outside LabTheme") }

/** Derives the Lab tokens from the current Jewel theme (Int UI palette, light or dark). */
@Composable
internal fun jewelLabColors(): LabColors {
    val global = JewelTheme.globalColors
    val palette = JewelTheme.colorPalette
    val dark = JewelTheme.isDark
    return if (dark) {
        LabColors(
            background = palette.gray(1),
            panel = global.panelBackground,
            raised = palette.gray(3),
            border = palette.gray(3),
            borderStrong = palette.gray(5),
            text = global.text.normal,
            textMuted = global.text.info,
            textDisabled = global.text.disabled,
            accent = palette.blue(6),
            onAccent = Color.White,
            selection = palette.blue(2),
            ok = palette.green(7),
            warning = palette.yellow(7),
            error = palette.red(8),
            code = palette.gray(2),
            specimen = palette.gray(3),
            target = palette.gray(2),
            overlay = palette.gray(12).copy(alpha = 0.9f),
            onOverlay = palette.gray(1),
            isDark = true,
        )
    } else {
        LabColors(
            background = palette.gray(14),
            panel = global.panelBackground,
            raised = palette.gray(12),
            border = palette.gray(11),
            borderStrong = palette.gray(9),
            text = global.text.normal,
            textMuted = global.text.info,
            textDisabled = global.text.disabled,
            accent = palette.blue(4),
            onAccent = Color.White,
            selection = palette.blue(11),
            ok = palette.green(4),
            warning = palette.yellow(1),
            error = palette.red(4),
            code = palette.gray(13),
            specimen = palette.gray(12),
            target = palette.gray(13),
            overlay = palette.gray(2).copy(alpha = 0.88f),
            onOverlay = palette.gray(14),
            isDark = false,
        )
    }
}

@Composable
internal fun jewelLabTypography(): LabTypography {
    val base = JewelTheme.defaultTextStyle
    val size = base.fontSize.value
    return LabTypography(
        title = base.copyWithSize((size + 5).sp, fontWeight = FontWeight.SemiBold),
        heading = base.copy(fontWeight = FontWeight.SemiBold),
        label = base,
        body = base,
        small = base.copyWithSize((size - 1).sp),
        mono = JewelTheme.editorTextStyle.copyWithSize(12.sp),
    )
}
