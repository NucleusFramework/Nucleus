@file:OptIn(
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.ui.ExperimentalComposeUiApi::class,
    androidx.compose.ui.text.ExperimentalTextApi::class,
)

package dev.nucleusframework.application.contextmenu

import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.ContextMenuState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.window.rememberPopupPositionProviderAtPosition
import dev.nucleusframework.application.spellcheck.LocalSpellcheckMenuSeparator
import kotlinx.coroutines.delay

private val MenuShape = RoundedCornerShape(8.dp)
private val ItemShape = RoundedCornerShape(4.dp)
private val UiFont = FontFamily("Segoe UI Variable Text")
private val IconFont = FontFamily("Segoe Fluent Icons")

private const val CHEVRON_GLYPH = "\uE76C"
private const val SUBMENU_OPEN_DELAY_MS = 200L
private const val SUBMENU_CLOSE_DELAY_MS = 160L

private class FluentColors(
    val surface: Color,
    val text: Color,
    val textDisabled: Color,
    val hover: Color,
    val separator: Color,
    val border: Color,
) {
    companion object {
        fun of(dark: Boolean): FluentColors =
            if (dark) {
                FluentColors(
                    surface = Color(red = 44, green = 44, blue = 44),
                    text = Color.White,
                    textDisabled = Color(red = 115, green = 115, blue = 115),
                    hover = Color.White.copy(alpha = 0.12f),
                    separator = Color(red = 61, green = 61, blue = 61),
                    border = Color(red = 61, green = 61, blue = 61),
                )
            } else {
                FluentColors(
                    surface = Color(red = 249, green = 249, blue = 249),
                    text = Color(red = 26, green = 26, blue = 26),
                    textDisabled = Color(red = 154, green = 154, blue = 154),
                    hover = Color.Black.copy(alpha = 0.08f),
                    separator = Color(red = 229, green = 229, blue = 229),
                    border = Color(red = 229, green = 229, blue = 229),
                )
            }
    }
}

@Composable
internal fun FluentContextMenuPopup(
    state: ContextMenuState,
    items: () -> List<ContextMenuItem>,
) {
    val status = state.status
    if (status !is ContextMenuState.Status.Open) return
    val interpreter = LocalContextMenuItemInterpreter.current
    val separator = LocalSpellcheckMenuSeparator.current
    val entries = items().map { item -> interpreter.interpret(item, separator) }
    if (entries.isEmpty()) {
        LaunchedEffect(status) {
            state.status = ContextMenuState.Status.Closed
        }
        return
    }
    val dark = isSystemInDarkTheme()
    // Click rect is already in pixels. Paint at window density so a subtree
    // zoom (Gallery 75%) does not scale the flyout.
    val menuDensity = LocalContextMenuDensity.current ?: LocalDensity.current
    Popup(
        popupPositionProvider = rememberPopupPositionProviderAtPosition(status.rect.center),
        onDismissRequest = { state.status = ContextMenuState.Status.Closed },
        properties = PopupProperties(focusable = true),
    ) {
        CompositionLocalProvider(LocalDensity provides menuDensity) {
            FluentMenuSurface(
                entries = entries,
                dark = dark,
                onDismiss = { state.status = ContextMenuState.Status.Closed },
            )
        }
    }
}

@Composable
private fun FluentMenuSurface(
    entries: List<ContextMenuEntry>,
    dark: Boolean,
    onDismiss: () -> Unit,
) {
    val colors = FluentColors.of(dark)
    val reserveIcon = entries.any { entry -> entry is ContextMenuEntry.Item && entry.icon != null }
    Column(
        Modifier
            .shadow(elevation = 16.dp, shape = MenuShape, clip = false)
            .width(IntrinsicSize.Max)
            .widthIn(min = 168.dp, max = 448.dp)
            .clip(MenuShape)
            .border(1.dp, colors.border, MenuShape)
            .background(colors.surface)
            .padding(vertical = 4.dp),
    ) {
        entries.forEach { entry ->
            when (entry) {
                is ContextMenuEntry.Separator ->
                    Box(
                        Modifier
                            .padding(horizontal = 12.dp, vertical = 4.dp)
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(colors.separator),
                    )
                is ContextMenuEntry.Item ->
                    FluentMenuRow(
                        label = entry.label,
                        enabled = entry.enabled,
                        icon = entry.icon?.toFluentGlyph(),
                        reserveIcon = reserveIcon,
                        chevron = false,
                        colors = colors,
                        onClick = {
                            onDismiss()
                            entry.onClick()
                        },
                    )
                is ContextMenuEntry.Submenu ->
                    FluentSubmenuRow(
                        entry = entry,
                        reserveIcon = reserveIcon,
                        dark = dark,
                        colors = colors,
                        onDismiss = onDismiss,
                    )
            }
        }
    }
}

@Composable
private fun FluentSubmenuRow(
    entry: ContextMenuEntry.Submenu,
    reserveIcon: Boolean,
    dark: Boolean,
    colors: FluentColors,
    onDismiss: () -> Unit,
) {
    val rowInteraction = remember { MutableInteractionSource() }
    val rowHovered by rowInteraction.collectIsHoveredAsState()
    var flyoutHovered by remember { mutableStateOf(false) }
    var showFlyout by remember { mutableStateOf(false) }
    LaunchedEffect(rowHovered, flyoutHovered) {
        if (rowHovered || flyoutHovered) {
            delay(SUBMENU_OPEN_DELAY_MS)
            showFlyout = true
        } else {
            delay(SUBMENU_CLOSE_DELAY_MS)
            showFlyout = false
        }
    }
    Box {
        FluentMenuRow(
            label = entry.label,
            enabled = true,
            icon = null,
            reserveIcon = reserveIcon,
            chevron = true,
            colors = colors,
            interactionSource = rowInteraction,
            onClick = { showFlyout = true },
        )
        if (showFlyout) {
            Popup(
                alignment = Alignment.TopEnd,
                offset = IntOffset(0, 0),
                properties = PopupProperties(focusable = false),
            ) {
                val flyoutInteraction = remember { MutableInteractionSource() }
                val hoveringFlyout by flyoutInteraction.collectIsHoveredAsState()
                LaunchedEffect(hoveringFlyout) {
                    flyoutHovered = hoveringFlyout
                }
                Box(Modifier.hoverable(flyoutInteraction)) {
                    FluentMenuSurface(
                        entries = entry.items,
                        dark = dark,
                        onDismiss = onDismiss,
                    )
                }
            }
        }
    }
}

@Composable
private fun FluentMenuRow(
    label: String,
    enabled: Boolean,
    icon: String?,
    reserveIcon: Boolean,
    chevron: Boolean,
    colors: FluentColors,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
    onClick: () -> Unit,
) {
    val hovered by interactionSource.collectIsHoveredAsState()
    val content = if (enabled) colors.text else colors.textDisabled
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp)
            .clip(ItemShape)
            .hoverable(interactionSource, enabled = enabled)
            .background(if (hovered && enabled) colors.hover else Color.Transparent)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                onClick = onClick,
            ).height(36.dp)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (reserveIcon) {
            if (icon != null) {
                BasicText(
                    text = icon,
                    style =
                        TextStyle(
                            color = content,
                            fontSize = 16.sp,
                            fontFamily = IconFont,
                        ),
                )
            } else {
                Spacer(Modifier.size(16.dp))
            }
            Spacer(Modifier.width(12.dp))
        }
        BasicText(
            text = label,
            modifier = Modifier.weight(1f),
            style =
                TextStyle(
                    color = content,
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    fontFamily = UiFont,
                ),
            maxLines = 1,
        )
        if (chevron) {
            Spacer(Modifier.width(12.dp))
            BasicText(
                text = CHEVRON_GLYPH,
                style =
                    TextStyle(
                        color = content,
                        fontSize = 12.sp,
                        fontFamily = IconFont,
                    ),
            )
        }
    }
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
