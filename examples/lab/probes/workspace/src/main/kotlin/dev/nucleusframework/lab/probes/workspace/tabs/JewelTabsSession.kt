package dev.nucleusframework.lab.probes.workspace.tabs

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.nucleusframework.application.NucleusApplicationScope
import dev.nucleusframework.application.Tab
import dev.nucleusframework.application.TabWindows
import dev.nucleusframework.lab.probes.workspace.common.DropClosedTab
import dev.nucleusframework.lab.probes.workspace.common.NewTabButton
import dev.nucleusframework.lab.probes.workspace.common.SaveableTabBody
import dev.nucleusframework.lab.probes.workspace.common.WorkspaceWindowFrame
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
import org.jetbrains.jewel.ui.component.TabData
import org.jetbrains.jewel.ui.component.TabStrip
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.theme.editorTabStyle

/**
 * The same workspace wearing IntelliJ's tab chrome. Everything a tab archetype needs from
 * its chrome is a modifier contract (`tabStripGeometry`, `tabSlot`, `tabDragHandle`), so
 * swapping the design system is this one strip. `LabTheme` is already Jewel's `IntUiTheme`,
 * light or dark, so the strip reads the Lab's own Jewel theme.
 */
@Composable
fun NucleusApplicationScope.JewelTabsSession(
    model: TabsSessionModel,
    close: () -> Unit,
) {
    TabWindows(
        workspace = model.workspace,
        strip = {
            val direction = if (model.live.rightToLeft) LayoutDirection.Rtl else LayoutDirection.Ltr
            CompositionLocalProvider(LocalLayoutDirection provides direction) { JewelEditorTabStrip(model) }
        },
        windowWrapper = { content -> WorkspaceWindowFrame(content) },
        onLastWindowClosed = close,
    )
    for (document in model.documents.documents) {
        key(document.id) {
            Tab(model.workspace, id = document.id, title = document.title) {
                SaveableTabBody(document, onNewTab = { model.documents.open() })
            }
            DropClosedTab(model.workspace, document.id, model.documents::forget)
        }
    }
}

/**
 * Jewel's [TabStrip] with one [TabData.Editor] per tab. Jewel's `TabData` carries no
 * `Modifier`, so slot, grip and click go on the tab's *content*, made to fill the tab —
 * otherwise the padding selects but does not drag. The drop slot is a Jewel tab holding
 * the stock ghost card, so a drag from another window opens a real gap here too.
 */
@Composable
private fun TabStripScope.JewelEditorTabStrip(model: TabsSessionModel) {
    val ghost = dropGhost
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        val tabData = tabs.mapIndexed { index, entry -> editorTab(index, entry) }.toMutableList()
        if (ghost != null) tabData.add(ghost.index.coerceIn(0, tabData.size), ghostTab(ghost))
        TabStrip(
            tabs = tabData,
            style = JewelTheme.editorTabStyle,
            modifier = Modifier.weight(1f).tabStripGeometry(workspace, group),
        )
        NewTabButton { model.documents.open() }
    }
    if (model.live.hoverPreview) {
        val preview = remember(model) { TabHoverPreview { JewelTabHoverCard(model) } }
        TabHoverPreviewPopup(preview)
    }
}

private fun ghostTab(ghost: TabDropGhost): TabData =
    TabData.Editor(selected = false, closable = false, content = { TabDropGhostCard(ghost) })

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
            Box(
                Modifier
                    .fillMaxSize()
                    .tabSlot(group, index)
                    .tabDragHandle(workspace, entry)
                    .clickable { workspace.select(entry.id) },
                contentAlignment = Alignment.CenterStart,
            ) {
                Text(entry.title, modifier = Modifier.tabContentAlpha(state = tabState))
            }
        },
    )

/** The whole card is the app's: Jewel colours, the first line of the draft, the thumbnail. */
@Composable
private fun TabHoverPreviewScope.JewelTabHoverCard(model: TabsSessionModel) {
    val document = model.documents.document(tab.id)
    Column(
        Modifier
            .width(260.dp)
            .background(JewelTheme.globalColors.panelBackground)
            .border(1.dp, JewelTheme.globalColors.borders.normal)
            .padding(8.dp),
    ) {
        Text(tab.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
        document?.draft?.substringBefore('\n')?.takeIf { it.isNotBlank() }?.let {
            Spacer(Modifier.height(6.dp))
            Text(it, color = JewelTheme.globalColors.text.info, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        thumbnail?.let { picture ->
            Spacer(Modifier.height(6.dp))
            Image(
                bitmap = picture,
                contentDescription = null,
                modifier = Modifier.fillMaxWidth().aspectRatio(picture.width.toFloat() / picture.height.toFloat()),
                contentScale = ContentScale.Crop,
            )
        }
    }
}
