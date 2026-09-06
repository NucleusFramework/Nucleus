package dev.nucleusframework.application.contextmenu

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Breeze kstyle/breezemetrics.h + colors/Breeze{Light,Dark}.colors
// SansSerif (not FontFamily("Noto Sans")): the named-family constructor is
// ExperimentalTextApi and can throw during BasicText place on Compose 1.12.
private val BreezeUiFont = FontFamily.SansSerif

private val BreezeAccent = Color(red = 61, green = 174, blue = 233)

internal val BreezeMenuTheme =
    ContextMenuFlyoutTheme(
        menuShape = RoundedCornerShape(5.dp),
        itemShape = RoundedCornerShape(5.dp),
        uiFont = BreezeUiFont,
        iconFont = BreezeUiFont,
        chevron = "›",
        chevronSize = 14.sp,
        chevronAlpha = 1f,
        minWidth = 128.dp,
        maxWidth = 320.dp,
        menuPadding = PaddingValues(4.dp),
        itemHeight = 30.dp,
        itemHorizontalPadding = 12.dp,
        itemOuterHorizontalPadding = 0.dp,
        separatorPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp),
        iconSize = 16.dp,
        iconGap = 4.dp,
        shadowPad = 12.dp,
        shadows = { BreezeMenuShadows },
        showIcons = true,
        shortcutGap = 16.dp,
        shortcutSize = 14.sp,
        shortcutAlpha = 0.70f,
        colors = ::breezeColors,
        glyph = { null },
        vector = ContextMenuIcon::toBreezeVector,
    )

/**
 * Breeze's `ShadowLarge` — the kstyle default for menus — from
 * `lookupShadowParams` in `kstyle/breezeshadowhelper.cpp`:
 * `CompositeShadowParams(QPoint(0, 5), ShadowParams(QPoint(0, 0), 20, 0.22),
 * ShadowParams(QPoint(0, -3), 10, 0.12))`. Each layer's offset is the
 * composite offset plus its own, its radius a CSS blur radius
 * (`BoxShadowRenderer` uses `radius / 2` as the standard deviation), at the
 * default `ShadowStrength` of 255 and the default black shadow colour.
 */
private val BreezeMenuShadows =
    listOf(
        ContextMenuBoxShadow(offsetY = 5.dp, blur = 20.dp, color = Color.Black.copy(alpha = 0.22f)),
        ContextMenuBoxShadow(offsetY = 2.dp, blur = 10.dp, color = Color.Black.copy(alpha = 0.12f)),
    )

private fun breezeColors(dark: Boolean): ContextMenuFlyoutColors =
    if (dark) {
        ContextMenuFlyoutColors(
            surface = Color(red = 32, green = 35, blue = 38),
            text = Color(red = 252, green = 252, blue = 252),
            textDisabled = Color(red = 161, green = 169, blue = 177),
            hover = BreezeAccent.copy(alpha = 0.30f),
            separator = Color(red = 252, green = 252, blue = 252, alpha = 0x26),
            border = Color(red = 252, green = 252, blue = 252, alpha = 0x33),
        )
    } else {
        ContextMenuFlyoutColors(
            surface = Color(red = 239, green = 240, blue = 241),
            text = Color(red = 35, green = 38, blue = 41),
            textDisabled = Color(red = 112, green = 125, blue = 138),
            hover = BreezeAccent.copy(alpha = 0.30f),
            separator = Color(red = 35, green = 38, blue = 41, alpha = 0x26),
            border = Color(red = 35, green = 38, blue = 41, alpha = 0x33),
        )
    }
