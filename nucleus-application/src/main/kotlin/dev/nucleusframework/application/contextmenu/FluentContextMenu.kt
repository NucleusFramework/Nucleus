@file:OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)

package dev.nucleusframework.application.contextmenu

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// WinUI 3 `MenuFlyout`, as a mouse / pen / keyboard right click opens it
// (`microsoft-ui-xaml`, `controls/dev/CommonStyles/MenuFlyout_themeresources.xaml`
// and `Common_themeresources_any.xaml`; the generic.xaml values they do not
// override). `GetShouldBeNarrow` puts the items in their `NarrowPadding` state
// for those devices — `MenuFlyoutItemThemePaddingNarrow` `11,4,11,5`, so a 14 px
// label (19 px tall after layout rounding) makes a 28 px row; the touch padding
// `11,8,11,9` is the one a finger gets.
private val FluentUiFont = FontFamily("Segoe UI Variable Text")
private val FluentIconFont = FontFamily("Segoe Fluent Icons")

internal val FluentMenuTheme =
    ContextMenuFlyoutTheme(
        // OverlayCornerRadius / ControlCornerRadius
        menuCornerRadius = 8.dp,
        itemShape = RoundedCornerShape(4.dp),
        uiFont = FluentUiFont,
        iconFont = FluentIconFont,
        // SubItemChevron: Glyph E974 (ChevronRightMed), FontSize 12, MenuFlyoutItemChevronMargin 24,0,0,-1
        chevron = "\uE974",
        chevronSize = 12.sp,
        chevronGap = 24.dp,
        // FlyoutThemeMinWidth; the presenter sets no MaxWidth
        minWidth = 96.dp,
        maxWidth = Dp.Unspecified,
        // MenuFlyoutPresenterThemePadding 0,2,0,2 (inside MenuFlyoutPresenterBorderThemeThickness 1)
        menuPadding = PaddingValues(vertical = 2.dp),
        itemHeight = 28.dp,
        itemHorizontalPadding = 11.dp,
        // MenuFlyoutItemMargin
        itemMargin = PaddingValues(horizontal = 4.dp, vertical = 2.dp),
        // MenuFlyoutSeparatorThemePadding -4,1,-4,1: edge to edge, 1 px above and below
        separatorPadding = PaddingValues(vertical = 1.dp),
        // IconRoot Viewbox 16x16; MenuFlyoutItemPlaceholderThemeThickness 28 = 16 + 12
        iconSize = 16.dp,
        iconGap = 12.dp,
        shadowPad = 0.dp,
        shadows = ::fluentShadows,
        showIcons = true,
        // KeyboardAcceleratorTextBlock: CaptionTextBlockStyle (12), Margin 24,4,0,0
        shortcutGap = 24.dp,
        shortcutSize = 12.sp,
        shortcutPadding = PaddingValues(top = 4.dp),
        colors = ::fluentColors,
        glyph = ContextMenuIcon::toFluentGlyph,
    )

/**
 * The shadow WinUI's `ThemeShadow` casts for a `MenuFlyout`, which sits at
 * `Translation.Z = 32` (context menus, command bars, flyouts), from
 * `GetDropShadowRecipe` in `dxaml/xcp/components/graphics/inc/DropShadowRecipe.h`:
 * elevation `Z / 2 = 16`, which is the top of the `2..16` band — no ambient
 * layer, one directional layer with a blur radius equal to the elevation
 * (plus one, added when the shadow is built), shifted down by half of it, at
 * `min(elevation / 100 + 0.06, 0.14)` in light and a flat `0.26` in dark.
 *
 * The composition `DropShadow.BlurRadius` is a Gaussian radius in the Direct2D
 * sense — WinUI reserves exactly that many pixels around the caster for the
 * shadow, so it is the ~3 σ extent, not the CSS radius of 2 σ: 17 px there is
 * an ~11 px CSS blur here.
 *
 * Not the Fluent 2 web token (`shadow16`, `0 0 8px 12%` + `0 8px 16px 14%`):
 * that is what Fluent UI React menus draw, but the flyout imitates the OS menu.
 */
private fun fluentShadows(dark: Boolean): List<ContextMenuBoxShadow> =
    listOf(
        ContextMenuBoxShadow(
            offsetY = 8.dp,
            blur = 11.dp,
            color = Color.Black.copy(alpha = if (dark) 0.26f else 0.14f),
        ),
    )

// The colour resources `MenuFlyout_themeresources.xaml` binds, from
// `Common_themeresources_any.xaml`'s Default (dark) and Light dictionaries. The
// translucent ones stay translucent: WinUI composites them at draw time.
private val TextFillColorPrimaryDark = Color.White
private val TextFillColorPrimaryLight = Color(red = 0, green = 0, blue = 0, alpha = 0xE4)
private val TextFillColorSecondaryDark = Color(red = 255, green = 255, blue = 255, alpha = 0xC5)
private val TextFillColorSecondaryLight = Color(red = 0, green = 0, blue = 0, alpha = 0x9E)
private val TextFillColorDisabledDark = Color(red = 255, green = 255, blue = 255, alpha = 0x5D)
private val TextFillColorDisabledLight = Color(red = 0, green = 0, blue = 0, alpha = 0x5C)
private val SubtleFillColorSecondaryDark = Color(red = 255, green = 255, blue = 255, alpha = 0x0F)
private val SubtleFillColorSecondaryLight = Color(red = 0, green = 0, blue = 0, alpha = 0x09)
private val DividerStrokeColorDefaultDark = Color(red = 255, green = 255, blue = 255, alpha = 0x15)
private val DividerStrokeColorDefaultLight = Color(red = 0, green = 0, blue = 0, alpha = 0x0F)
private val SurfaceStrokeColorFlyoutDark = Color(red = 0, green = 0, blue = 0, alpha = 0x33)
private val SurfaceStrokeColorFlyoutLight = Color(red = 0, green = 0, blue = 0, alpha = 0x0F)

/**
 * `MenuFlyoutPresenterBackground` is a `DesktopAcrylicBackdrop`; these are its
 * fallbacks (`AcrylicBackgroundFillColorDefaultBrush`'s `FallbackColor`), i.e.
 * the menu as Windows draws it with transparency effects off. The acrylic
 * itself — a blur of what is behind the menu, tinted and luminosity-blended —
 * needs the compositor and is not reproduced.
 */
private val AcrylicFallbackDark = Color(red = 44, green = 44, blue = 44)
private val AcrylicFallbackLight = Color(red = 249, green = 249, blue = 249)

/**
 * `MenuFlyout_themeresources.xaml`'s brush bindings: item foreground
 * `TextFillColorPrimary`, disabled `TextFillColorDisabled`, pointer-over
 * background `SubtleFillColorSecondary`, separator `DividerStrokeColorDefault`,
 * presenter border `SurfaceStrokeColorFlyout` (drawn outside the background,
 * `BackgroundSizing = InnerBorderEdge`), chevron and keyboard accelerator text
 * `TextFillColorSecondary`.
 */
private fun fluentColors(dark: Boolean): ContextMenuFlyoutColors =
    if (dark) {
        ContextMenuFlyoutColors(
            surface = AcrylicFallbackDark,
            text = TextFillColorPrimaryDark,
            textDisabled = TextFillColorDisabledDark,
            hover = SubtleFillColorSecondaryDark,
            separator = DividerStrokeColorDefaultDark,
            border = SurfaceStrokeColorFlyoutDark,
            chevron = TextFillColorSecondaryDark,
            shortcut = TextFillColorSecondaryDark,
        )
    } else {
        ContextMenuFlyoutColors(
            surface = AcrylicFallbackLight,
            text = TextFillColorPrimaryLight,
            textDisabled = TextFillColorDisabledLight,
            hover = SubtleFillColorSecondaryLight,
            separator = DividerStrokeColorDefaultLight,
            border = SurfaceStrokeColorFlyoutLight,
            chevron = TextFillColorSecondaryLight,
            shortcut = TextFillColorSecondaryLight,
        )
    }

internal fun ContextMenuIcon.toFluentGlyph(): String? =
    when (this) {
        ContextMenuIcon.Cut -> "\uE8C6"
        ContextMenuIcon.Copy -> "\uE8C8"
        ContextMenuIcon.Paste -> "\uE77F"
        ContextMenuIcon.SelectAll -> "\uE8B3"
        ContextMenuIcon.Delete -> "\uE74D"
        ContextMenuIcon.Folder -> "\uE8B7"
        is ContextMenuIcon.SfSymbol -> null
    }
