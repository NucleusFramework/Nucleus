package dev.nucleusframework.lab.probes.workspace.tabs

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import dev.nucleusframework.application.NucleusApplicationScope
import dev.nucleusframework.application.Tab
import dev.nucleusframework.application.TabWindows
import dev.nucleusframework.lab.probes.workspace.common.DropClosedTab
import dev.nucleusframework.lab.probes.workspace.common.NewTabButton
import dev.nucleusframework.lab.probes.workspace.common.SaveableTabBody
import dev.nucleusframework.lab.probes.workspace.common.WorkspaceWindowFrame
import dev.nucleusframework.lab.probes.workspace.common.documentHoverPreview
import dev.nucleusframework.window.tao.TabReorderAnimation
import dev.nucleusframework.window.tao.TabStrip
import dev.nucleusframework.window.tao.TabStripScope

/**
 * The archetype: `TabWindows` composes one window per group, every document is declared
 * once as a `Tab`, and the last window closing ends the session. Every window wears the
 * Lab's frame and title bar ([WorkspaceWindowFrame]); only the strip changes with
 * [TabsSessionModel.strip] — Jewel's by default, Nucleus' stock one on request. Everything
 * a tab archetype needs from its chrome is a modifier contract (`tabStripGeometry`,
 * `tabSlot`, `tabDragHandle`), so swapping the design system is this one slot.
 */
@Composable
fun NucleusApplicationScope.TabsSession(
    model: TabsSessionModel,
    close: () -> Unit,
) {
    TabWindows(
        workspace = model.workspace,
        strip = {
            val direction = if (model.live.rightToLeft) LayoutDirection.Rtl else LayoutDirection.Ltr
            CompositionLocalProvider(LocalLayoutDirection provides direction) {
                when (model.strip) {
                    TabStripChrome.Jewel -> JewelEditorTabStrip(model)
                    TabStripChrome.Stock -> StockTabStrip(model)
                }
            }
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
 * The stock [TabStrip] — which publishes the drop geometry other windows' drags resolve
 * against — with the live knobs applied. Its colours come from the title bar style, i.e.
 * the Lab's; the "+" and the hover card's subtitle are the Lab's own components.
 */
@Composable
private fun TabStripScope.StockTabStrip(model: TabsSessionModel) {
    val live = model.live
    val preview = remember(model) { documentHoverPreview { model.documents.document(it)?.subtitle } }
    TabStrip(
        hoverPreview = preview.takeIf { live.hoverPreview },
        reorderAnimation = TabReorderAnimation.takeIf { live.animateReorder },
        trailing = { NewTabButton { model.documents.open() } },
    )
}
