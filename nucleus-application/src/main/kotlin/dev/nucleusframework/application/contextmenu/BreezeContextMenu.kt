@file:OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)

package dev.nucleusframework.application.contextmenu

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Breeze kstyle/breezemetrics.h + colors/Breeze{Light,Dark}.colors
private val BreezeUiFont = FontFamily("Noto Sans")

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
        maxWidth = Dp.Unspecified,
        menuPadding = PaddingValues(4.dp),
        itemHeight = 30.dp,
        itemHorizontalPadding = 12.dp,
        itemOuterHorizontalPadding = 0.dp,
        separatorPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp),
        iconSize = 16.dp,
        iconGap = 4.dp,
        shadowElevation = 10.dp,
        shadowPad = 12.dp,
        ambientShadow = Color.Black.copy(alpha = 0.18f),
        spotShadow = Color.Black.copy(alpha = 0.10f),
        showIcons = true,
        shortcutGap = 16.dp,
        shortcutSize = 14.sp,
        shortcutAlpha = 0.70f,
        colors = ::breezeColors,
        glyph = ContextMenuIcon::toBreezeGlyph,
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

internal fun ContextMenuIcon.toBreezeGlyph(): String? =
    when (this) {
        ContextMenuIcon.Cut -> "\u2702"
        ContextMenuIcon.Copy -> "\u2398"
        ContextMenuIcon.Paste -> "\u2399"
        ContextMenuIcon.SelectAll -> "\u2611"
        ContextMenuIcon.Delete -> "\u232B"
        ContextMenuIcon.Folder -> "\u25A1"
        is ContextMenuIcon.SfSymbol -> null
    }
