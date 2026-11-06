package dev.nucleusframework.lab.probes.workspace.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.nucleusframework.lab.designsystem.Divider
import dev.nucleusframework.lab.designsystem.LabDimens
import dev.nucleusframework.lab.designsystem.LabShapes
import dev.nucleusframework.lab.designsystem.LabTheme
import dev.nucleusframework.lab.designsystem.ScrollableColumn
import dev.nucleusframework.lab.designsystem.SelectableRow
import dev.nucleusframework.lab.designsystem.Text
import dev.nucleusframework.lab.probes.workspace.common.Document
import dev.nucleusframework.lab.probes.workspace.common.NoTabSelected

/** A book's text: the tab's own body, so its scroll position follows the tab to another window. */
@Composable
fun BookText(
    model: ReaderModel,
    book: Document<List<String>>,
) {
    val chapters = book.extra
    val chapter = model.stateOf(book.id).chapter.coerceIn(chapters.indices)
    Column(Modifier.fillMaxSize()) {
        // The tab's own scroll state: saveable, so it follows the tab to another window.
        ScrollableColumn(
            Modifier.weight(1f).fillMaxWidth(),
            scrollState = rememberScrollState(),
            contentPadding = PaddingValues(24.dp),
            verticalArrangement = Arrangement.spacedBy(LabDimens.block),
        ) {
            Text("${book.title} · ${chapters[chapter]}", style = LabTheme.typography.title)
            repeat(PARAGRAPHS) { index ->
                Text("${index + 1}. ${SAMPLE.repeat(1 + index % 3)}")
            }
        }
        Divider()
        Row(
            Modifier.fillMaxWidth().height(28.dp).padding(horizontal = 24.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Library  ›  ${book.title}  ›  ${chapters[chapter]}",
                style = LabTheme.typography.small,
                color = LabTheme.colors.textMuted,
            )
        }
    }
}

/**
 * A pane's body for the book its window shows: the library selects tabs, the contents pick
 * the chapter, the rest list what they hold for the chapter in view.
 */
@Composable
fun PaneContent(
    model: ReaderModel,
    pane: Pane,
    book: Document<List<String>>?,
) {
    if (book == null) {
        NoTabSelected("No book open")
        return
    }
    val state = model.stateOf(book.id)
    val chapter = book.extra[state.chapter.coerceIn(book.extra.indices)]
    ScrollableColumn(
        Modifier.fillMaxSize().background(LabTheme.colors.background),
        contentPadding = PaddingValues(LabDimens.gap),
        verticalArrangement = Arrangement.spacedBy(LabDimens.lineGap),
    ) {
        when (pane) {
            Pane.Library -> for (candidate in model.books.documents) {
                PaneItem(candidate.title, candidate.id == book.id) {
                    model.tabs.select(candidate.id)
                }
            }
            Pane.Contents ->
                book.extra.forEachIndexed { index, name ->
                    PaneItem(name, index == state.chapter) {
                        state.chapter =
                            index
                    }
                }
            else ->
                repeat(ITEMS) { index ->
                    PaneItem(
                        "${pane.title} $chapter·${index + 1}",
                        state.selected(pane) == index,
                    ) { state.select(pane, index) }
                }
        }
    }
}

@Composable
private fun PaneItem(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    SelectableRow(selected = selected, onClick = onClick) { Text(label) }
}

@Composable
fun ActivityBar(content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxHeight()
            .width(48.dp)
            .background(LabTheme.colors.panel)
            .padding(vertical = LabDimens.gap),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(LabDimens.gap),
    ) { content() }
}

@Composable
fun BarButton(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val colors = LabTheme.colors
    Box(
        Modifier
            .size(36.dp)
            .clip(LabShapes.pill)
            .background(if (selected) colors.accent.copy(alpha = 0.18f) else Color.Transparent)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = LabTheme.typography.heading, color = if (selected) colors.accent else colors.textMuted)
    }
}

private const val PARAGRAPHS = 40
private const val ITEMS = 40
private const val SAMPLE =
    "The morning was grey and still, and the road ran on beside the river past the mill and the old stone bridge. "
