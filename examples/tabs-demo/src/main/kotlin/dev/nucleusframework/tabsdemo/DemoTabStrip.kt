package dev.nucleusframework.tabsdemo

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.nucleusframework.window.styling.LocalTitleBarStyle
import dev.nucleusframework.window.tao.TabStrip
import dev.nucleusframework.window.tao.TabStripScope

/**
 * The strip of one window: the stock [TabStrip], plus a new-tab button right
 * after the last tab.
 *
 * The stock strip is what publishes the geometry a tab dragged from another
 * window is dropped onto, which is why chrome is added *around* its tabs
 * rather than in place of them. A strip written from scratch would have to
 * apply `Modifier.tabStripGeometry`, `Modifier.tabSlot` and
 * `Modifier.tabDragHandle` itself.
 */
@Composable
fun TabStripScope.DemoTabStrip(onNewTab: () -> Unit) {
    TabStrip(trailing = { NewTabButton(onNewTab) })
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
