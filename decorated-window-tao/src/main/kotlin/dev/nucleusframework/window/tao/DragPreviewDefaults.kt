package dev.nucleusframework.window.tao

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.nucleusframework.window.styling.LocalTitleBarStyle

/**
 * The one look every drop preview of the workspaces has — the card that
 * follows the pointer out of a window, the same card drawn on the space a
 * release will fill, and the faint outline of a place that could be dropped
 * on: a tinted, rounded surface in the title bar's content colour.
 *
 * One surface rather than one per gesture, so a tab and a panel, a preview in
 * hand and a preview on its target, all read as the same thing.
 */
internal object DragPreviewDefaults {
    val CornerRadius: Dp = 8.dp
    val BorderWidth: Dp = 1.dp

    /** The card: what is being dragged, in hand or on the space it will take. */
    const val FILL_ALPHA = 0.22f
    const val BORDER_ALPHA = 0.55f

    /** The hint: a place that could be dropped on, but is not the one aimed at. */
    const val HINT_FILL_ALPHA = 0.06f
    const val HINT_BORDER_ALPHA = 0.22f
}

/**
 * The tinted, rounded surface of a drop preview; [hint] draws it at the
 * intensity of a place that is merely on offer.
 */
@Composable
internal fun DragPreviewSurface(
    modifier: Modifier = Modifier,
    hint: Boolean = false,
    content: @Composable BoxScope.() -> Unit = {},
) {
    val accent = LocalTitleBarStyle.current.colors.content
    val shape = RoundedCornerShape(DragPreviewDefaults.CornerRadius)
    val fill = if (hint) DragPreviewDefaults.HINT_FILL_ALPHA else DragPreviewDefaults.FILL_ALPHA
    val border = if (hint) DragPreviewDefaults.HINT_BORDER_ALPHA else DragPreviewDefaults.BORDER_ALPHA
    Box(
        modifier =
            modifier
                .background(accent.copy(alpha = fill), shape)
                .border(DragPreviewDefaults.BorderWidth, accent.copy(alpha = border), shape),
        content = content,
    )
}
