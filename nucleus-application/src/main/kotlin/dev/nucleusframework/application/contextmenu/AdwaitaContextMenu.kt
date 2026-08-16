@file:OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)

package dev.nucleusframework.application.contextmenu

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// libadwaita _common.scss / _menus.scss / _popovers.scss
private val AdwaitaUiFont = FontFamily("Adwaita Sans")

internal val AdwaitaMenuTheme =
    ContextMenuFlyoutTheme(
        menuShape = RoundedCornerShape(15.dp),
        itemShape = RoundedCornerShape(9.dp),
        uiFont = AdwaitaUiFont,
        iconFont = AdwaitaUiFont,
        chevron = "›",
        chevronSize = 16.sp,
        chevronAlpha = 0.30f,
        minWidth = 120.dp,
        maxWidth = Dp.Unspecified,
        menuPadding = PaddingValues(6.dp),
        itemHeight = 32.dp,
        itemHorizontalPadding = 12.dp,
        itemOuterHorizontalPadding = 0.dp,
        separatorPadding = PaddingValues(vertical = 6.dp),
        iconSize = 16.dp,
        iconGap = 6.dp,
        shadowElevation = 8.dp,
        shadowPad = 16.dp,
        ambientShadow = Color.Black.copy(alpha = 0.09f),
        spotShadow = Color.Black.copy(alpha = 0.05f),
        showIcons = false,
        colors = ::adwaitaColors,
        glyph = { null },
    )

private fun adwaitaColors(dark: Boolean): ContextMenuFlyoutColors =
    if (dark) {
        ContextMenuFlyoutColors(
            surface = Color(red = 54, green = 54, blue = 58),
            text = Color.White,
            textDisabled = Color.White.copy(alpha = 0.50f),
            hover = Color.White.copy(alpha = 0.10f),
            separator = Color(red = 0, green = 0, blue = 6, alpha = 0x40),
            border = Color.Black.copy(alpha = 0.05f),
        )
    } else {
        ContextMenuFlyoutColors(
            surface = Color.White,
            text = Color(red = 0, green = 0, blue = 6, alpha = 0xCC),
            textDisabled = Color(red = 0, green = 0, blue = 6, alpha = 0x66),
            hover = Color(red = 0, green = 0, blue = 6, alpha = 0x1A),
            separator = Color(red = 0, green = 0, blue = 6, alpha = 0x12),
            border = Color.Black.copy(alpha = 0.05f),
        )
    }
