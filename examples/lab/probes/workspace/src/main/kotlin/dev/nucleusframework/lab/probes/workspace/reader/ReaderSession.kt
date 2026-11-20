package dev.nucleusframework.lab.probes.workspace.reader

import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import dev.nucleusframework.application.NucleusApplicationScope
import dev.nucleusframework.application.Satellite
import dev.nucleusframework.application.Tab
import dev.nucleusframework.application.TabWindows
import dev.nucleusframework.lab.designsystem.Divider
import dev.nucleusframework.lab.designsystem.LabDimens
import dev.nucleusframework.lab.probes.workspace.common.DropClosedTab
import dev.nucleusframework.lab.probes.workspace.common.NewTabButton
import dev.nucleusframework.lab.probes.workspace.common.WorkspaceWindowFrame
import dev.nucleusframework.lab.probes.workspace.common.documentHoverPreview
import dev.nucleusframework.lab.probes.workspace.common.rememberTabGroups
import dev.nucleusframework.window.tao.DockLayout
import dev.nucleusframework.window.tao.DockSide
import dev.nucleusframework.window.tao.JoinSatelliteWorkspace
import dev.nucleusframework.window.tao.TabStrip
import dev.nucleusframework.window.tao.TabStripScope
import dev.nucleusframework.window.tao.TabWindowGroup

/**
 * A library reader: books are tabs, every pane is a satellite of its window's dock, hung on
 * `windowBodyWrapper` so the strip stays the top of the window and a tab change touches no
 * panel. Left to right by default; right to left is a live switch.
 */
@Composable
fun NucleusApplicationScope.ReaderSession(
    model: ReaderModel,
    close: () -> Unit,
) {
    val direction = if (model.live.rightToLeft) LayoutDirection.Rtl else LayoutDirection.Ltr
    TabWindows(
        workspace = model.tabs,
        strip = { CompositionLocalProvider(LocalLayoutDirection provides direction) { ReaderTabStrip(model) } },
        windowWrapper = { content ->
            WorkspaceWindowFrame {
                model.tabs
                    .groupOf(
                        nucleusWindow.unsafe.taoWindow,
                    )?.let { JoinSatelliteWorkspace(model.docks.of(it.id)) }
                content()
            }
        },
        windowBodyWrapper = { body ->
            val group = model.tabs.groupOf(nucleusWindow.unsafe.taoWindow)
            if (group == null) {
                body()
            } else {
                CompositionLocalProvider(LocalLayoutDirection provides direction) { ReaderBody(model, group, body) }
            }
        },
        onLastWindowClosed = close,
    )
    for (book in model.books.documents) {
        key(book.id) {
            Tab(model.tabs, id = book.id, title = book.title) { BookText(model, book) }
            DropClosedTab(model.tabs, book.id, model::forget)
        }
    }
    for (group in rememberTabGroups(model.tabs)) {
        key(group.id) { WindowPanes(model, group) }
    }
}

@Composable
private fun TabStripScope.ReaderTabStrip(model: ReaderModel) {
    val preview = remember(model) { documentHoverPreview { model.books.document(it)?.subtitle } }
    TabStrip(hoverPreview = preview, trailing = { NewTabButton(model::openDocument) })
}

/** One satellite per pane, declared against this window's workspace, drawing its selected book. */
@Composable
private fun NucleusApplicationScope.WindowPanes(
    model: ReaderModel,
    group: TabWindowGroup,
) {
    val workspace = model.docks.of(group.id)
    DisposableEffect(model, group.id) { onDispose { model.docks.forget(group.id) } }
    val book = model.tabs.selectedTab(group)?.let { model.books.document(it.id) }
    val style = model.live.style
    for (pane in Pane.entries) {
        Satellite(
            workspace = workspace,
            id = pane.idIn(group.id),
            title = pane.title,
            initialPlacement = pane.home,
            initiallyOpen = pane.openAtStart,
            dockSides = pane.dockSides,
            floatable = !pane.fixed,
            reorderable = !pane.fixed,
            header = { PaneHeader(style) },
            floatingCaption = { PaneMoveAffordance() },
        ) {
            PaneContent(model, pane, book)
        }
    }
}

/** Activity bars around the dock; the selected book's text is the dock's content. */
@Composable
private fun ReaderBody(
    model: ReaderModel,
    group: TabWindowGroup,
    text: @Composable () -> Unit,
) {
    val live = model.live
    Row(Modifier.fillMaxSize()) {
        ActivityBar {
            for (pane in listOf(Pane.Library, Pane.Contents, Pane.Notes)) {
                BarButton(pane.title.take(1), selected = model.isOpen(group.id, pane)) { model.toggle(group.id, pane) }
            }
        }
        Divider(Orientation.Vertical, Modifier.fillMaxHeight())
        DockLayout(
            workspace = model.docks.of(group.id),
            modifier = Modifier.weight(1f).fillMaxHeight(),
            sideOrder = live.sideOrder,
            layeredSides = if (live.layeredRight) setOf(DockSide.Right) else emptySet(),
            splitter = { ReaderSplitter(live.style) },
            panel = { body -> PaneCard(live.style) { body() } },
        ) {
            PaneCard(live.style) { text() }
        }
        Divider(Orientation.Vertical, Modifier.fillMaxHeight())
        ActivityBar {
            for (pane in listOf(Pane.Glossary, Pane.Bookmarks, Pane.Search)) {
                BarButton(pane.title.take(1), selected = model.isOpen(group.id, pane)) { model.toggle(group.id, pane) }
            }
            Spacer(Modifier.height(LabDimens.gap))
            BarButton("◫", selected = live.style == ReaderStyle.Islands) {
                val next = if (live.style == ReaderStyle.Islands) ReaderStyle.Classic else ReaderStyle.Islands
                model.live = live.copy(style = next)
            }
        }
    }
}
