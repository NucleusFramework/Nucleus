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
        menuCornerRadius = 5.dp,
        itemShape = RoundedCornerShape(5.dp),
        uiFont = BreezeUiFont,
        iconFont = BreezeUiFont,
        chevron = "›",
        chevronSize = 14.sp,
        chevronGap = 4.dp,
        minWidth = 128.dp,
        maxWidth = 320.dp,
        // Frame width 1 (the border ring) + MenuItem_MarginWidth 3.
        menuPadding = PaddingValues(3.dp),
        itemHeight = 30.dp,
        itemHorizontalPadding = 12.dp,
        itemMargin = PaddingValues(0.dp),
        separatorPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp),
        iconSize = 16.dp,
        iconGap = 4.dp,
        shadowPad = 12.dp,
        shadows = { BreezeMenuShadows },
        showIcons = true,
        shortcutGap = 16.dp,
        shortcutSize = 14.sp,
        shortcutPadding = PaddingValues(0.dp),
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

/**
 * Breeze strokes its menu frame *over* the filled rect (`renderMenuFrame`: one
 * `drawRoundedRect` with both brush and pen), so its 20 % outline is seen
 * against the menu's own background. The flyout paints the ring outside the
 * surface, so the colours below are that composite, already resolved.
 */
private fun breezeColors(dark: Boolean): ContextMenuFlyoutColors =
    if (dark) {
        val text = Color(red = 252, green = 252, blue = 252)
        ContextMenuFlyoutColors(
            surface = Color(red = 32, green = 35, blue = 38),
            text = text,
            textDisabled = Color(red = 161, green = 169, blue = 177),
            hover = BreezeAccent.copy(alpha = 0.30f),
            separator = text.copy(alpha = 0x26 / 255f),
            // (252, 252, 252) at 0x33 over the surface
            border = Color(red = 76, green = 78, blue = 81),
            chevron = text,
            shortcut = text.copy(alpha = 0.70f),
        )
    } else {
        val text = Color(red = 35, green = 38, blue = 41)
        ContextMenuFlyoutColors(
            surface = Color(red = 239, green = 240, blue = 241),
            text = text,
            textDisabled = Color(red = 112, green = 125, blue = 138),
            hover = BreezeAccent.copy(alpha = 0.30f),
            separator = text.copy(alpha = 0x26 / 255f),
            // (35, 38, 41) at 0x33 over the surface
            border = Color(red = 198, green = 200, blue = 201),
            chevron = text,
            shortcut = text.copy(alpha = 0.70f),
        )
    }
