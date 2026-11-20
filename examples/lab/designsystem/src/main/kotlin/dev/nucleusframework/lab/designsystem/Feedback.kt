package dev.nucleusframework.lab.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Nothing to show yet: one muted sentence, the same everywhere. */
@Composable
fun EmptyState(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(text, style = LabTheme.typography.small, color = LabTheme.colors.textMuted, modifier = modifier)
}

/** Help text: how to drive the probe or what to look at. Never a heading. */
@Composable
fun Hint(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(text, style = LabTheme.typography.small, color = LabTheme.colors.textMuted, modifier = modifier)
}

/**
 * Monospace, selectable text in a code surface: commands, JSON, manifests, process output.
 * [followTail] keeps it scrolled to the end while it grows.
 */
@Composable
fun CodeBlock(
    text: String,
    modifier: Modifier = Modifier,
    maxHeight: Dp = 320.dp,
    followTail: Boolean = false,
    empty: String = "(empty)",
) {
    val scroll = rememberScrollState()
    if (followTail) LaunchedEffect(text.length) { scroll.scrollTo(scroll.maxValue) }
    ScrollableColumn(
        modifier =
            modifier
                .fillMaxWidth()
                .heightIn(max = maxHeight)
                .clip(LabShapes.block)
                .background(LabSurfaces.code)
                .border(1.dp, LabTheme.colors.border, LabShapes.block),
        scrollState = scroll,
        contentPadding = PaddingValues(LabDimens.block),
    ) {
        SelectionContainer {
            Text(
                text.ifEmpty { empty },
                style = LabTheme.typography.mono,
                color = if (text.isEmpty()) LabTheme.colors.textMuted else LabTheme.colors.text,
            )
        }
    }
}
