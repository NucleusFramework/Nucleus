@file:OptIn(
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.ui.ExperimentalComposeUiApi::class,
    androidx.compose.ui.text.ExperimentalTextApi::class,
)

package dev.nucleusframework.application.contextmenu

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
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.window.rememberPopupPositionProviderAtPosition
import kotlinx.coroutines.delay

private const val SUBMENU_OPEN_DELAY_MS = 200L
private const val SUBMENU_CLOSE_DELAY_MS = 160L

internal class ContextMenuFlyoutColors(
    val surface: Color,
    val text: Color,
    val textDisabled: Color,
    val hover: Color,
    val separator: Color,
    val border: Color,
)

internal class ContextMenuFlyoutTheme(
    val menuShape: RoundedCornerShape,
    val itemShape: RoundedCornerShape,
    val uiFont: FontFamily,
    val iconFont: FontFamily,
    val chevron: String,
    val chevronSize: TextUnit,
    val chevronAlpha: Float,
    val minWidth: Dp,
    val maxWidth: Dp,
    val menuPadding: PaddingValues,
    val itemHeight: Dp,
    val itemHorizontalPadding: Dp,
    val itemOuterHorizontalPadding: Dp,
    val separatorPadding: PaddingValues,
    val iconSize: Dp,
    val iconGap: Dp,
    val shadowElevation: Dp,
    val shadowPad: Dp,
    val ambientShadow: Color,
    val spotShadow: Color,
    val showIcons: Boolean,
    val colors: (dark: Boolean) -> ContextMenuFlyoutColors,
    val glyph: (ContextMenuIcon) -> String?,
)

@Composable
internal fun ContextMenuFlyout(
    status: ContextMenuState.Status.Open,
    entries: List<ContextMenuEntry>,
    theme: ContextMenuFlyoutTheme,
    onDismiss: () -> Unit,
) {
    val dark = isSystemInDarkTheme()
    val menuDensity = LocalContextMenuDensity.current ?: LocalDensity.current
    Popup(
        popupPositionProvider = rememberPopupPositionProviderAtPosition(status.rect.center),
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        CompositionLocalProvider(LocalDensity provides menuDensity) {
            ContextMenuFlyoutSurface(
                entries = entries,
                theme = theme,
                dark = dark,
                onDismiss = onDismiss,
            )
        }
    }
}

@Composable
private fun ContextMenuFlyoutSurface(
    entries: List<ContextMenuEntry>,
    theme: ContextMenuFlyoutTheme,
    dark: Boolean,
    onDismiss: () -> Unit,
) {
    val colors = theme.colors(dark)
    val reserveIcon =
        theme.showIcons &&
            entries.any { entry ->
                entry is ContextMenuEntry.Item && theme.glyph(entry.icon ?: return@any false) != null
            }
    Box(Modifier.padding(theme.shadowPad)) {
        Column(
            Modifier
                .shadow(
                    elevation = theme.shadowElevation,
                    shape = theme.menuShape,
                    clip = false,
                    ambientColor = theme.ambientShadow,
                    spotColor = theme.spotShadow,
                ).width(IntrinsicSize.Max)
                .widthIn(min = theme.minWidth, max = theme.maxWidth)
                .clip(theme.menuShape)
                .border(1.dp, colors.border, theme.menuShape)
                .background(colors.surface)
                .padding(theme.menuPadding),
        ) {
            entries.forEach { entry ->
                when (entry) {
                    is ContextMenuEntry.Separator ->
                        Box(
                            Modifier
                                .padding(theme.separatorPadding)
                                .fillMaxWidth()
                                .height(1.dp)
                                .background(colors.separator),
                        )
                    is ContextMenuEntry.Item ->
                        ContextMenuFlyoutRow(
                            label = entry.label,
                            enabled = entry.enabled,
                            icon = entry.icon?.let(theme.glyph),
                            reserveIcon = reserveIcon,
                            chevron = false,
                            theme = theme,
                            colors = colors,
                            onClick = {
                                onDismiss()
                                entry.onClick()
                            },
                        )
                    is ContextMenuEntry.Submenu ->
                        ContextMenuFlyoutSubmenu(
                            entry = entry,
                            reserveIcon = reserveIcon,
                            theme = theme,
                            dark = dark,
                            colors = colors,
                            onDismiss = onDismiss,
                        )
                }
            }
        }
    }
}

@Composable
private fun ContextMenuFlyoutSubmenu(
    entry: ContextMenuEntry.Submenu,
    reserveIcon: Boolean,
    theme: ContextMenuFlyoutTheme,
    dark: Boolean,
    colors: ContextMenuFlyoutColors,
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
        ContextMenuFlyoutRow(
            label = entry.label,
            enabled = true,
            icon = null,
            reserveIcon = reserveIcon,
            chevron = true,
            theme = theme,
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
                    ContextMenuFlyoutSurface(
                        entries = entry.items,
                        theme = theme,
                        dark = dark,
                        onDismiss = onDismiss,
                    )
                }
            }
        }
    }
}

@Composable
private fun ContextMenuFlyoutRow(
    label: String,
    enabled: Boolean,
    icon: String?,
    reserveIcon: Boolean,
    chevron: Boolean,
    theme: ContextMenuFlyoutTheme,
    colors: ContextMenuFlyoutColors,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
    onClick: () -> Unit,
) {
    val hovered by interactionSource.collectIsHoveredAsState()
    val content = if (enabled) colors.text else colors.textDisabled
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = theme.itemOuterHorizontalPadding)
            .clip(theme.itemShape)
            .hoverable(interactionSource, enabled = enabled)
            .background(if (hovered && enabled) colors.hover else Color.Transparent)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                onClick = onClick,
            ).height(theme.itemHeight)
            .padding(horizontal = theme.itemHorizontalPadding),
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
                            fontFamily = theme.iconFont,
                        ),
                )
            } else {
                Spacer(Modifier.size(theme.iconSize))
            }
            Spacer(Modifier.width(theme.iconGap))
        }
        BasicText(
            text = label,
            modifier = Modifier.weight(1f),
            style =
                TextStyle(
                    color = content,
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    fontFamily = theme.uiFont,
                ),
            maxLines = 1,
        )
        if (chevron) {
            Spacer(Modifier.width(theme.iconGap))
            BasicText(
                text = theme.chevron,
                style =
                    TextStyle(
                        color = content.copy(alpha = theme.chevronAlpha),
                        fontSize = theme.chevronSize,
                        fontFamily = theme.iconFont,
                    ),
            )
        }
    }
}
