package dev.nucleusframework.window.jewel

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import dev.nucleusframework.application.spellcheck.LocalSpellcheckMenuSeparator
import org.jetbrains.jewel.ui.component.ContextMenuDivider

/**
 * Provides Jewel's [ContextMenuDivider] to [LocalSpellcheckMenuSeparator].
 *
 * Compile-time Jewel reference — no reflection. Installed automatically by
 * [JewelDecoratedWindow] and [JewelDecoratedDialog]. Apps that build their
 * own Jewel chrome around `DecoratedWindow` should wrap content with this.
 */
@Composable
public fun ProvideJewelSpellcheckMenu(content: @Composable () -> Unit) {
    CompositionLocalProvider(
        LocalSpellcheckMenuSeparator provides ContextMenuDivider,
        content = content,
    )
}
