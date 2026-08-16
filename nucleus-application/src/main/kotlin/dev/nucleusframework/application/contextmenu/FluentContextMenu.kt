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
        shadowElevation = 16.dp,
        shadowPad = 0.dp,
        ambientShadow = Color.Black.copy(alpha = 0.20f),
        spotShadow = Color.Black.copy(alpha = 0.20f),
        showIcons = true,
        colors = ::fluentColors,
        glyph = ContextMenuIcon::toFluentGlyph,
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
