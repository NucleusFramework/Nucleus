@file:OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)

package dev.nucleusframework.application.contextmenu

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val FluentUiFont = FontFamily("Segoe UI Variable Text")
private val FluentIconFont = FontFamily("Segoe Fluent Icons")

internal val FluentMenuTheme =
    ContextMenuFlyoutTheme(
        menuShape = RoundedCornerShape(8.dp),
        itemShape = RoundedCornerShape(4.dp),
        uiFont = FluentUiFont,
        iconFont = FluentIconFont,
        chevron = "\uE76C",
        chevronSize = 12.sp,
        chevronAlpha = 1f,
        minWidth = 168.dp,
        maxWidth = 448.dp,
        menuPadding = PaddingValues(vertical = 4.dp),
        itemHeight = 36.dp,
        itemHorizontalPadding = 12.dp,
        itemOuterHorizontalPadding = 4.dp,
        separatorPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
        iconSize = 16.dp,
        iconGap = 12.dp,
        shadowPad = 0.dp,
        shadows = ::fluentShadows,
        showIcons = true,
        shortcutGap = 36.dp,
        shortcutSize = 12.sp,
        shortcutAlpha = 0.60f,
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

private fun fluentColors(dark: Boolean): ContextMenuFlyoutColors =
    if (dark) {
        ContextMenuFlyoutColors(
            surface = Color(red = 44, green = 44, blue = 44),
            text = Color.White,
            textDisabled = Color(red = 115, green = 115, blue = 115),
            hover = Color.White.copy(alpha = 0.12f),
            separator = Color(red = 61, green = 61, blue = 61),
            border = Color(red = 61, green = 61, blue = 61),
        )
    } else {
        ContextMenuFlyoutColors(
            surface = Color(red = 249, green = 249, blue = 249),
            text = Color(red = 26, green = 26, blue = 26),
            textDisabled = Color(red = 154, green = 154, blue = 154),
            hover = Color.Black.copy(alpha = 0.08f),
            separator = Color(red = 229, green = 229, blue = 229),
            border = Color(red = 229, green = 229, blue = 229),
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
