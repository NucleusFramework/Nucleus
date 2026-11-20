package dev.nucleusframework.lab.probes.workspace.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import dev.nucleusframework.lab.designsystem.Actions
import dev.nucleusframework.lab.designsystem.Hint
import dev.nucleusframework.lab.designsystem.LabDimens
import dev.nucleusframework.lab.designsystem.LabTheme
import dev.nucleusframework.lab.designsystem.PrimaryAction
import dev.nucleusframework.lab.designsystem.Readout
import dev.nucleusframework.lab.designsystem.ScrollableColumn
import dev.nucleusframework.lab.designsystem.SecondaryAction
import dev.nucleusframework.lab.designsystem.Section
import dev.nucleusframework.lab.designsystem.Text
import dev.nucleusframework.lab.designsystem.TextFieldRow
import dev.nucleusframework.lab.designsystem.Tone
import dev.nucleusframework.window.tao.TabScope

/**
 * The body of a tab under test: state that must follow the tab between windows (saveable)
 * next to state that must not (plain `remember`), plus the tab's own workspace calls.
 * Composed by `TabWindows` in whichever window holds the tab.
 */
@Composable
fun TabScope.SaveableTabBody(
    document: Document<*>,
    onNewTab: () -> Unit,
    above: @Composable () -> Unit = {},
) {
    var draft by rememberSaveable { mutableStateOf(document.draft) }
    var savedClicks by rememberSaveable { mutableIntStateOf(0) }
    // Not saveable, on purpose: the counterexample. A move rebuilds this subtree in the
    // other window's composition, where a plain `remember` starts over.
    var plainClicks by remember { mutableIntStateOf(0) }
    val group = tab.group

    ScrollableColumn(
        Modifier.fillMaxSize(),
        // Saveable too: the scroll position is part of what must follow the tab.
        scrollState = rememberScrollState(),
        contentPadding = PaddingValues(LabDimens.page),
        verticalArrangement = Arrangement.spacedBy(LabDimens.blockGap),
    ) {
        Text(document.title, style = LabTheme.typography.title)
        Hint(document.subtitle)
        above()
        Section("This tab") {
            Actions {
                PrimaryAction("New tab", onClick = onNewTab)
                SecondaryAction("Select", enabled = !tab.isSelected) { select() }
                SecondaryAction("Move to its own window", enabled = (group?.ids?.size ?: 0) > 1) {
                    workspace.tearOffFromItsWindow(tab.id)
                }
                SecondaryAction("Close") { close() }
            }
            Readout("window", group?.id)
            Readout("its tabs", group?.ids?.joinToString())
        }
        Section("State that must follow the tab") {
            TextFieldRow("rememberSaveable draft", draft, singleLine = false, minLines = 3) { draft = it }
            Actions {
                PrimaryAction("saveable: $savedClicks") { savedClicks++ }
                SecondaryAction("plain remember: $plainClicks") { plainClicks++ }
            }
            Hint("After a move: draft, saveable counter and scroll position come back; the plain counter is 0.")
        }
        Section("Scroll past these") {
            for (line in 1..NOTE_LINES) Readout("$line", "${document.title} — line $line", tone = Tone.Muted)
        }
    }
}

private const val NOTE_LINES = 30
