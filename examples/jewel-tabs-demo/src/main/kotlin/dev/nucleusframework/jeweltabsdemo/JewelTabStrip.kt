package dev.nucleusframework.jeweltabsdemo

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.nucleusframework.window.tao.TabDropGhost
import dev.nucleusframework.window.tao.TabDropGhostCard
import dev.nucleusframework.window.tao.TabEntry
import dev.nucleusframework.window.tao.TabHoverPreview
import dev.nucleusframework.window.tao.TabHoverPreviewPopup
import dev.nucleusframework.window.tao.TabHoverPreviewScope
import dev.nucleusframework.window.tao.TabStripScope
import dev.nucleusframework.window.tao.dropGhost
import dev.nucleusframework.window.tao.tabDragHandle
import dev.nucleusframework.window.tao.tabSlot
import dev.nucleusframework.window.tao.tabStripGeometry
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.IconButton
import org.jetbrains.jewel.ui.component.TabData
import org.jetbrains.jewel.ui.component.TabStrip
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.theme.editorTabStyle

/**
 * The tab strip of one window, drawn by Jewel: [TabStrip] with one
 * [TabData.Editor] per tab of the group, in IntelliJ's editor-tab style.
 *
 * The split of responsibilities is the whole point of this demo — Jewel owns
 * how a tab looks (shape, hover, selection underline, close button, the
 * scrollbar once the tabs overflow), and the workspace owns what the tabs are:
 *
 *  - [tabStripGeometry] on the strip itself publishes the drop target, so a tab
 *    dragged out of another window can be released here;
 *  - [tabSlot] on each tab's content is what turns a pointer position into an
 *    insertion index;
 *  - [tabDragHandle] on the same element is the grip that drags the tab between
 *    windows;
 *  - `onClick` / `onClose` are workspace calls.
 *
 * Jewel's [TabData] carries no `Modifier`, so the three per-tab modifiers go
 * on the tab's *content* — which is therefore made to fill the tab, and to
 * carry the click that selects as well. Anything less and the tab has two
 * different active areas: Jewel's own `onClick` covering the whole tab, and a
 * drag grip covering only the label, which claims the press wherever it sits.
 * What is left over is a sliver of padding that selects but does not drag.
 *
 * A `Modifier` on [TabData] would remove the need for any of this: the slot,
 * the grip and the click would go on the tab itself.
 *
 * The hover card is the other half of that contract: [TabHoverPreviewPopup]
 * needs nothing but the slots this strip already marks, and the card itself is
 * drawn here, in Jewel's own colours ([JewelTabHoverCard]) — the workspace
 * neither knows nor imposes what a preview looks like.
 */
@Composable
fun TabStripScope.JewelEditorTabStrip(
    demo: DemoState,
    onNewTab: () -> Unit,
) {
    val entries = tabs
    // A tab dragged over this strip from another window is shown taking its
    // place: the same card it travels under, as wide as it is, opened among
    // the tabs where the release would put it.
    val ghost = dropGhost
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start,
    ) {
        val tabData = entries.mapIndexed { index, entry -> editorTab(index, entry) }.toMutableList<TabData>()
        if (ghost != null) tabData.add(ghost.index, ghostTab(ghost))
        TabStrip(
            tabs = tabData,
            style = JewelTheme.editorTabStyle,
            modifier = Modifier.weight(1f).tabStripGeometry(workspace, group),
        )
        NewTabButton(onNewTab)
    }
    // Anchored on the slots marked above, so it follows the pointer from tab to
    // tab without this strip tracking anything itself.
    val preview = remember(demo) { TabHoverPreview { JewelTabHoverCard(demo) } }
    TabHoverPreviewPopup(preview)
}

/**
 * The hover card of one tab, drawn by the demo from end to end: the file name,
 * its first line, and the picture the workspace kept of its editor.
 *
 * Nothing of the stock card is used — [TabHoverPreview] takes the whole
 * composable, so an app's preview looks like the rest of its design system
 * rather than like the window chrome.
 */
@Composable
private fun TabHoverPreviewScope.JewelTabHoverCard(demo: DemoState) {
    val document = demo.document(tab.id)
    Column(
        modifier =
            Modifier
                .width(CARD_WIDTH_DP.dp)
                .background(JewelTheme.globalColors.panelBackground)
                .border(1.dp, JewelTheme.globalColors.borders.normal)
                .padding(CARD_PADDING_DP.dp),
    ) {
        Text(tab.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
        val draft = document?.draft ?: ""
        val firstLine = draft.substringBefore('\n')
        if (firstLine.isNotBlank()) {
            Spacer(Modifier.height(CARD_GAP_DP.dp))
            Text(
                text = firstLine,
                color = JewelTheme.globalColors.text.info,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        thumbnail?.let { picture ->
            Spacer(Modifier.height(CARD_GAP_DP.dp))
            Image(
                bitmap = picture,
                contentDescription = null,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(picture.width.toFloat() / picture.height.toFloat()),
                contentScale = ContentScale.Crop,
            )
        }
    }
}

/** The slot a tab from another window would take, as a Jewel tab that is nothing but the card. */
private fun ghostTab(ghost: TabDropGhost): TabData =
    TabData.Editor(selected = false, closable = false, content = { TabDropGhostCard(ghost) })

/** One document as a Jewel editor tab, its whole surface the slot, the grip and the click. */
private fun TabStripScope.editorTab(
    index: Int,
    entry: TabEntry,
): TabData =
    TabData.Editor(
        selected = entry.id == group.selectedId,
        closable = true,
        onClose = { workspace.close(entry.id) },
        onClick = { workspace.select(entry.id) },
        content = { tabState ->
            // One element for the whole gesture surface, filling
            // the tab: the slot the strip publishes, the grip a
            // drag starts from and the click that selects are
            // the same box, so there is no part of a tab that
            // reacts to one and not the others. Putting them on
            // the label alone leaves selection to the padding
            // around it — a sliver at the edges — while the
            // label drags, which is exactly as odd as it sounds.
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .tabSlot(group, index)
                        .tabDragHandle(workspace, entry)
                        .clickable { workspace.select(entry.id) },
                contentAlignment = Alignment.CenterStart,
            ) {
                // `tabContentAlpha` is Jewel's own: the label
                // dims exactly as it does in the IDE when the
                // tab is unselected or its window loses focus.
                Text(entry.title, modifier = Modifier.tabContentAlpha(state = tabState))
            }
        },
    )

/** The "+" of a browser, as an IntelliJ icon button. */
@Composable
private fun NewTabButton(onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.padding(horizontal = 4.dp).size(24.dp)) {
        Text("+")
    }
}

private const val CARD_WIDTH_DP = 260
private const val CARD_PADDING_DP = 8
private const val CARD_GAP_DP = 6
