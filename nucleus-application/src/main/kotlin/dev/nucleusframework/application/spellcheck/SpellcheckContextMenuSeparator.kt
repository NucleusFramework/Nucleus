@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package dev.nucleusframework.application.spellcheck

import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.ContextMenuRepresentation
import androidx.compose.foundation.ContextMenuState
import androidx.compose.foundation.LocalContextMenuRepresentation
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.window.rememberPopupPositionProviderAtPosition
import dev.nucleusframework.application.contextmenu.LocalNativeContextMenu

/**
 * Hairline sentinel for Compose's default context menu. Jewel apps provide
 * `ContextMenuDivider` via [LocalSpellcheckMenuSeparator] instead.
 */
public object SpellcheckContextMenuSeparator : ContextMenuItem(
    label = "",
    enabled = false,
    onClick = {},
)

/**
 * Separator [SpellcheckContextMenu] inserts around suggestions.
 *
 * Defaults to [SpellcheckContextMenuSeparator]. Jewel windows provide
 * `ContextMenuDivider` through `ProvideJewelSpellcheckMenu`.
 */
public val LocalSpellcheckMenuSeparator: ProvidableCompositionLocal<ContextMenuItem> =
    staticCompositionLocalOf { SpellcheckContextMenuSeparator }

/**
 * Draws [SpellcheckContextMenuSeparator] as a hairline. Skipped when Jewel
 * already supplies `ContextMenuDivider` — we must not replace that chrome.
 */
@Composable
internal fun ProvideSpellcheckSeparators(content: @Composable () -> Unit) {
    if (LocalNativeContextMenu.current) {
        content()
        return
    }
    if (LocalSpellcheckMenuSeparator.current !== SpellcheckContextMenuSeparator) {
        content()
        return
    }
    val dark = isSystemInDarkTheme()
    val delegate = LocalContextMenuRepresentation.current
    val representation = remember(dark, delegate) { SpellcheckSeparatorRepresentation(delegate, dark) }
    CompositionLocalProvider(LocalContextMenuRepresentation provides representation, content = content)
}

private class SpellcheckSeparatorRepresentation(
    private val delegate: ContextMenuRepresentation,
    private val dark: Boolean,
) : ContextMenuRepresentation {
    @Composable
    override fun Representation(
        state: ContextMenuState,
        items: () -> List<ContextMenuItem>,
    ) {
        val status = state.status
        if (status !is ContextMenuState.Status.Open) {
            delegate.Representation(state, items)
            return
        }
        val resolved = items()
        if (resolved.none { it === SpellcheckContextMenuSeparator }) {
            delegate.Representation(state) { resolved }
            return
        }
        Popup(
            popupPositionProvider = rememberPopupPositionProviderAtPosition(status.rect.center),
            onDismissRequest = { state.status = ContextMenuState.Status.Closed },
            properties = PopupProperties(focusable = true),
        ) {
            SpellcheckMenuColumn(
                dark = dark,
                items = resolved,
                onDismiss = { state.status = ContextMenuState.Status.Closed },
            )
        }
    }
}

@Suppress("MagicNumber")
private object SpellcheckMenuChrome {
    val backgroundDark: Color = Color(red = 18, green = 18, blue = 18)
    val backgroundLight: Color = Color.White
    val textDark: Color = Color.White
    val textLight: Color = Color.Black
    val hoverDark: Color = Color.White.copy(alpha = 0.04f)
    val hoverLight: Color = Color.Black.copy(alpha = 0.04f)
    const val SEPARATOR_ALPHA: Float = 0.24f
    const val DISABLED_ALPHA: Float = 0.38f
}

@Composable
private fun SpellcheckMenuColumn(
    dark: Boolean,
    items: List<ContextMenuItem>,
    onDismiss: () -> Unit,
) {
    val background = if (dark) SpellcheckMenuChrome.backgroundDark else SpellcheckMenuChrome.backgroundLight
    val text = if (dark) SpellcheckMenuChrome.textDark else SpellcheckMenuChrome.textLight
    val hover = if (dark) SpellcheckMenuChrome.hoverDark else SpellcheckMenuChrome.hoverLight
    Column(
        Modifier
            .shadow(8.dp)
            .background(background)
            .padding(vertical = 4.dp)
            .width(IntrinsicSize.Max),
    ) {
        items.forEach { item ->
            if (item === SpellcheckContextMenuSeparator) {
                Box(
                    Modifier
                        .padding(vertical = 8.dp)
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(text.copy(alpha = SpellcheckMenuChrome.SEPARATOR_ALPHA)),
                )
            } else {
                SpellcheckMenuRow(item = item, text = text, hover = hover, onDismiss = onDismiss)
            }
        }
    }
}

@Composable
private fun SpellcheckMenuRow(
    item: ContextMenuItem,
    text: Color,
    hover: Color,
    onDismiss: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered = interaction.collectIsHoveredAsState().value
    Box(
        Modifier
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = item.enabled,
            ) {
                onDismiss()
                item.onClick()
            }.background(if (hovered && item.enabled) hover else Color.Transparent)
            .fillMaxWidth()
            .sizeIn(minWidth = 112.dp, maxWidth = 280.dp, minHeight = 32.dp)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        BasicText(
            text = item.label,
            style =
                TextStyle(
                    color = if (item.enabled) text else text.copy(alpha = SpellcheckMenuChrome.DISABLED_ALPHA),
                ),
        )
    }
}
