package dev.nucleusframework.readerdockdemo

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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.nucleusframework.window.styling.LocalTitleBarStyle
import dev.nucleusframework.window.tao.TabHoverPreview
import dev.nucleusframework.window.tao.TabHoverPreviewCard
import dev.nucleusframework.window.tao.TabStrip
import dev.nucleusframework.window.tao.TabStripScope

/**
 * The seforim of one window: the stock [TabStrip], plus the button that opens
 * another sefer after the last tab, and the card shown under a sefer the
 * pointer rests on.
 *
 * The stock strip is what publishes the geometry a tab dragged from another
 * window is dropped onto, so the reader's own chrome goes *around* its tabs
 * rather than in place of them.
 *
 * The card is right to left like everything else here: it hangs from the tab's
 * *right* edge and grows leftwards, because the strip is composed in an
 * `Rtl` direction and the card follows the reading direction it is given. It
 * is never shown for the sefer being read — that page is on screen already.
 */
@Composable
fun TabStripScope.ReaderTabStrip(
    reader: ReaderState,
    onNewBook: () -> Unit,
) {
    // The stock card with a line of the reader's own: the workspace knows a
    // tab's title, so the number of chapters is looked up by the demo from the
    // tab's id. The picture under it is the page the sefer was left on.
    val preview =
        remember(reader) {
            TabHoverPreview {
                TabHoverPreviewCard(
                    subtitle = {
                        val colors = LocalTitleBarStyle.current.colors
                        val chapters = reader.book(tab.id)?.chapters
                        Text(
                            text = "${chapters?.size ?: 0} פרקים",
                            color = colors.content.copy(alpha = SUBTITLE_ALPHA),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    },
                )
            }
        }
    TabStrip(hoverPreview = preview, trailing = { NewBookButton(onNewBook) })
}

/** Opens another sefer in this workspace. */
@Composable
private fun NewBookButton(onClick: () -> Unit) {
    val colors = LocalTitleBarStyle.current.colors
    Box(
        modifier =
            Modifier
                .padding(horizontal = BUTTON_PADDING_DP.dp)
                .size(BUTTON_SIZE_DP.dp)
                .clip(CircleShape)
                .clickable(onClick = onClick)
                .pointerHoverIcon(PointerIcon.Hand),
        contentAlignment = Alignment.Center,
    ) {
        Text("+", color = colors.content, fontSize = BUTTON_GLYPH_SP.sp)
    }
}

private const val BUTTON_PADDING_DP = 6
private const val BUTTON_SIZE_DP = 22
private const val BUTTON_GLYPH_SP = 15
private const val SUBTITLE_ALPHA = 0.7f
