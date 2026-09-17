package dev.nucleusframework.tabsdemo

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.nucleusframework.window.styling.LocalTitleBarStyle
import dev.nucleusframework.window.tao.TabHoverPreview
import dev.nucleusframework.window.tao.TabHoverPreviewCard
import dev.nucleusframework.window.tao.TabStrip
import dev.nucleusframework.window.tao.TabStripScope

/**
 * The strip of one window: the stock [TabStrip], plus a new-tab button right
 * after the last tab and a hover card under the tab the pointer rests on.
 *
 * The stock strip is what publishes the geometry a tab dragged from another
 * window is dropped onto, which is why chrome is added *around* its tabs
 * rather than in place of them. A strip written from scratch would have to
 * apply `Modifier.tabStripGeometry`, `Modifier.tabSlot` and
 * `Modifier.tabDragHandle` itself.
 */
@Composable
fun TabStripScope.DemoTabStrip(
    demo: DemoState,
    onNewTab: () -> Unit,
) {
    // The card is the stock one with a second line of the demo's own: the
    // workspace knows a tab's title and nothing else, so anything past it —
    // here the file's path — is looked up by the app from the tab's id.
    // `TabHoverPreview(content = …)` would replace the card outright.
    val preview =
        remember(demo) {
            TabHoverPreview {
                TabHoverPreviewCard(
                    subtitle = {
                        val colors = LocalTitleBarStyle.current.colors
                        val path = demo.document(tab.id)?.path ?: ""
                        Text(
                            text = path,
                            color = colors.content.copy(alpha = SUBTITLE_ALPHA),
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                )
            }
        }
    TabStrip(hoverPreview = preview, trailing = { NewTabButton(onNewTab) })
}

/** The "+" of a browser: opens a document in this workspace. */
@Composable
private fun NewTabButton(onClick: () -> Unit) {
    val colors = LocalTitleBarStyle.current.colors
    Box(
        modifier =
            Modifier
                .padding(horizontal = 6.dp)
                .size(22.dp)
                .clip(CircleShape)
                .clickable(onClick = onClick)
                .pointerHoverIcon(PointerIcon.Hand),
        contentAlignment = Alignment.Center,
    ) {
        Text("+", color = colors.content, fontSize = 15.sp)
    }
}

private const val SUBTITLE_ALPHA = 0.7f
