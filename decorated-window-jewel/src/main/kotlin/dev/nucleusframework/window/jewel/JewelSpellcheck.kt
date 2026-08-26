package dev.nucleusframework.window.jewel

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import dev.nucleusframework.application.contextmenu.LocalContextMenuDivider
import dev.nucleusframework.application.contextmenu.LocalContextMenuItemInterpreter
import org.jetbrains.jewel.ui.component.ContextMenuDivider

/**
 * Publishes Jewel's [ContextMenuDivider] as the renderer's divider through
 * [LocalContextMenuDivider], so features that build menu items (spellcheck)
 * emit separators Jewel's chrome can actually draw, and installs
 * [JewelContextMenuInterpreter] so a native context menu can map Jewel
 * Cut / Copy / Paste action types to OS icons and accelerators.
 *
 * Compile-time Jewel reference — no reflection. Installed automatically by
 * [JewelDecoratedWindow] and [JewelDecoratedDialog]. Apps that build their
 * own Jewel chrome around `DecoratedWindow` should wrap content with this.
 */
@Composable
public fun ProvideJewelSpellcheckMenu(content: @Composable () -> Unit) {
    CompositionLocalProvider(
        LocalContextMenuDivider provides ContextMenuDivider,
        LocalContextMenuItemInterpreter provides JewelContextMenuInterpreter,
        content = content,
    )
}
