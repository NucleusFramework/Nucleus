@file:OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)

package dev.nucleusframework.application.contextmenu

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// libadwaita _common.scss / _menus.scss / _popovers.scss
private val AdwaitaUiFont = FontFamily("Adwaita Sans")

internal val AdwaitaMenuTheme =
    ContextMenuFlyoutTheme(
        menuCornerRadius = 15.dp,
        itemShape = RoundedCornerShape(9.dp),
        uiFont = AdwaitaUiFont,
        iconFont = AdwaitaUiFont,
        chevron = "›",
        chevronSize = 16.sp,
        chevronGap = 6.dp,
        minWidth = 120.dp,
        maxWidth = 280.dp,
        menuPadding = PaddingValues(6.dp),
        itemHeight = 32.dp,
        itemHorizontalPadding = 12.dp,
        itemMargin = PaddingValues(0.dp),
        separatorPadding = PaddingValues(vertical = 6.dp),
        iconSize = 16.dp,
        iconGap = 6.dp,
        shadowPad = 16.dp,
        shadows = { AdwaitaMenuShadows },
        showIcons = false,
        shortcutGap = 24.dp,
        shortcutSize = 14.sp,
        shortcutPadding = PaddingValues(0.dp),
        colors = ::adwaitaColors,
        glyph = { null },
    )

/**
 * `popover > contents { box-shadow: ... }` in libadwaita's `_popovers.scss`:
 * `0 0 0 1px RGB(0 0 0 / 5%)`, `0 1px 5px 1px RGB(0 0 0 / 9%)`,
 * `0 2px 14px 3px RGB(0 0 0 / 5%)`. The first, a hairline ring, is the
 * [ContextMenuFlyoutColors.border]; the other two are the shadow proper. Same
 * in the dark variant.
 */
private val AdwaitaMenuShadows =
    listOf(
        ContextMenuBoxShadow(offsetY = 1.dp, blur = 5.dp, spread = 1.dp, color = Color.Black.copy(alpha = 0.09f)),
        ContextMenuBoxShadow(offsetY = 2.dp, blur = 14.dp, spread = 3.dp, color = Color.Black.copy(alpha = 0.05f)),
    )

/**
 * `separator { background: $border_color; }` in libadwaita's `_misc.scss`, with
 * `$border_color: color-mix(in srgb, currentColor var(--border-opacity), transparent)`
 * and `--border-opacity: 15%`. `currentColor` inside a menu is `popover_fg_color`,
 * so the rule is 15 % of the *text* colour — white on a dark menu, and
 * `RGB(0 0 6 / 80%)` premultiplied down to 12 % on a light one. It is not
 * `popover_shade_color` (25 % black in dark), which libadwaita keeps for scroll
 * undershoots: that reads as a dark gap instead of Adwaita's light hairline.
 */
private const val ADWAITA_BORDER_OPACITY = 0.15f

private fun adwaitaColors(dark: Boolean): ContextMenuFlyoutColors =
    if (dark) {
        ContextMenuFlyoutColors(
            surface = Color(red = 54, green = 54, blue = 58),
            text = Color.White,
            textDisabled = Color.White.copy(alpha = 0.50f),
            hover = Color.White.copy(alpha = 0.10f),
            separator = Color.White.copy(alpha = ADWAITA_BORDER_OPACITY),
            border = Color.Black.copy(alpha = 0.05f),
            chevron = Color.White.copy(alpha = 0.30f),
            shortcut = Color.White.copy(alpha = 0.55f),
        )
    } else {
        ContextMenuFlyoutColors(
            surface = Color.White,
            text = Color(red = 0, green = 0, blue = 6, alpha = 0xCC),
            textDisabled = Color(red = 0, green = 0, blue = 6, alpha = 0x66),
            hover = Color(red = 0, green = 0, blue = 6, alpha = 0x1A),
            separator = Color(red = 0, green = 0, blue = 6).copy(alpha = 0.80f * ADWAITA_BORDER_OPACITY),
            border = Color.Black.copy(alpha = 0.05f),
            chevron = Color(red = 0, green = 0, blue = 6, alpha = 0xCC).copy(alpha = 0.30f),
            shortcut = Color(red = 0, green = 0, blue = 6, alpha = 0xCC).copy(alpha = 0.55f),
        )
    }
