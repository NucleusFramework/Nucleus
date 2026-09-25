package dev.nucleusframework.jeweltabsdemo

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.nucleusframework.window.tao.TabScope
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.DefaultButton
import org.jetbrains.jewel.ui.component.GroupHeader
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.TextArea

/**
 * The body of one tab: a small editor whose state has to survive being dragged
 * to another window, plus the workspace controls and a live read-out.
 *
 * Composed by `TabWindows` in whichever window holds the tab — the same call
 * site in every window, which is what lets the saveable values below travel.
 */
@Composable
fun TabScope.EditorContent(
    demo: DemoState,
    document: Document,
) {
    val workspace = demo.workspace
    val group = tab.group

    // `rememberTextFieldState` is saveable, so the draft crosses windows with
    // the tab for free — as does the scroll position below.
    val draft = rememberTextFieldState(document.draft)
    var savedClicks by rememberSaveable { mutableIntStateOf(0) }
    val scroll = rememberScrollState()
    // Not saveable, on purpose: the counterexample. A move rebuilds this
    // subtree in the other window's composition and a plain `remember` starts
    // over there.
    var plainClicks by remember { mutableIntStateOf(0) }

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .verticalScroll(scroll)
                .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Jewel 0.39's `Typography` object is deprecated in favour of a
        // `JewelTheme.typography` that this version does not ship yet, so the
        // heading is derived from the theme's own text style instead.
        Text(
            document.title,
            style = JewelTheme.defaultTextStyle.copy(fontSize = TITLE_SP.sp, fontWeight = FontWeight.SemiBold),
        )
        Text(
            "The tabs above are Jewel's own — IntelliJ's editor-tab chrome, styled by " +
                "JewelTheme.editorTabStyle — driven by the Nucleus TabWorkspace. Drag one out " +
                "of the strip and drop it on the desktop: it lands in a window of its own. " +
                "Drag it back onto the other window's strip and it is inserted where you drop " +
                "it. Drag the only tab of a window and the window itself follows the pointer, " +
                "then merges into the strip it lands on.",
        )

        GroupHeader("This tab")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DefaultButton(onClick = { demo.open() }) { Text("New tab") }
            OutlinedButton(onClick = { select() }, enabled = !tab.isSelected) { Text("Select") }
            OutlinedButton(onClick = { close() }) { Text("Close this tab") }
        }

        GroupHeader("State that follows the tab")
        // A bounded height, and it has to be: Jewel's TextArea scrolls
        // internally, which an enclosing Column(verticalScroll) would measure
        // with an unbounded height.
        TextArea(state = draft, modifier = Modifier.fillMaxWidth().height(EDITOR_HEIGHT_DP.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DefaultButton(onClick = { savedClicks++ }) { Text("saveable: $savedClicks") }
            OutlinedButton(onClick = { plainClicks++ }) { Text("plain remember: $plainClicks") }
        }
        Text(
            "Type in the editor, click both counters, scroll down a little, then drag this tab " +
                "into another window. The draft, the saveable counter and the scroll position " +
                "come back; the plain one restarts at 0 — the two windows are two compositions, " +
                "and only saveable state crosses.",
            color = JewelTheme.globalColors.text.info,
        )

        GroupHeader("Layout")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { demo.saveLayout() }) { Text("Save layout") }
            OutlinedButton(onClick = { demo.restoreLayout() }, enabled = demo.savedLayout != null) {
                Text("Restore layout")
            }
        }

        GroupHeader("Live state")
        StateLine("windows", workspace.groups.size.toString())
        StateLine("tabs, all windows", workspace.tabs.size.toString())
        StateLine("this window's group", group?.id ?: "—")
        StateLine("its tabs", group?.ids?.joinToString(", ") ?: "—")
        StateLine("dragging", workspace.draggedTab?.title ?: "—")
        StateLine("drop preview", workspace.dropPreview?.let { "${it.group.id} @ ${it.index}" } ?: "—")

        // Something to scroll past, so the saved scroll position shows.
        GroupHeader("Notes")
        for (line in 1..NOTE_LINES) {
            Text("$line. ${document.title} — line $line", color = JewelTheme.globalColors.text.info)
        }
    }
}

@Composable
private fun StateLine(
    name: String,
    value: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(name, fontFamily = FontFamily.Monospace, color = JewelTheme.globalColors.text.info)
        Text(value, fontFamily = FontFamily.Monospace)
    }
}

private const val TITLE_SP = 18
private const val EDITOR_HEIGHT_DP = 110
private const val NOTE_LINES = 24
