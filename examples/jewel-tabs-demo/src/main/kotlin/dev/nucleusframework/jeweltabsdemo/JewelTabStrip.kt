package dev.nucleusframework.jeweltabsdemo

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.nucleusframework.window.tao.TabStripScope
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
 */
@Composable
fun TabStripScope.JewelEditorTabStrip(onNewTab: () -> Unit) {
    val entries = tabs
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start,
    ) {
        TabStrip(
            tabs =
                entries.mapIndexed { index, entry ->
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
                },
            style = JewelTheme.editorTabStyle,
            modifier = Modifier.weight(1f).tabStripGeometry(workspace, group),
        )
        NewTabButton(onNewTab)
    }
}

/** The "+" of a browser, as an IntelliJ icon button. */
@Composable
private fun NewTabButton(onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.padding(horizontal = 4.dp).size(24.dp)) {
        Text("+")
    }
}
