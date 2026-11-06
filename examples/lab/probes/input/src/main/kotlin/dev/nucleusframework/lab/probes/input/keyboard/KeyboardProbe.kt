package dev.nucleusframework.lab.probes.input.keyboard

import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.utf16CodePoint
import androidx.compose.ui.unit.dp
import dev.nucleusframework.lab.core.Check
import dev.nucleusframework.lab.core.Domain
import dev.nucleusframework.lab.core.Probe
import dev.nucleusframework.lab.core.ProbeDescriptor
import dev.nucleusframework.lab.core.ProbeId
import dev.nucleusframework.lab.designsystem.Actions
import dev.nucleusframework.lab.designsystem.Counters
import dev.nucleusframework.lab.designsystem.EventLog
import dev.nucleusframework.lab.designsystem.Hint
import dev.nucleusframework.lab.designsystem.LogEntry
import dev.nucleusframework.lab.designsystem.ProbeLayout
import dev.nucleusframework.lab.designsystem.Readout
import dev.nucleusframework.lab.designsystem.SecondaryAction
import dev.nucleusframework.lab.designsystem.SubHeading
import dev.nucleusframework.lab.designsystem.TargetArea
import dev.nucleusframework.lab.designsystem.TextArea
import dev.nucleusframework.lab.designsystem.Tone
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metrox.viewmodel.metroViewModel
import kotlinx.coroutines.flow.drop

@ContributesIntoSet(AppScope::class)
@Inject
class KeyboardProbe : Probe {
    override val descriptor =
        ProbeDescriptor(
            id = ID,
            title = "Keyboard & IME",
            domain = Domain.Input,
            summary = "Do key events, modifiers, dead keys, press-and-hold and IME composition reach Compose intact?",
            modules = listOf("decorated-window-tao"),
            checks =
                listOf(
                    Check(
                        "balanced",
                        "Every KeyDown gets its KeyUp: 'Held' is empty once all keys are released, even after Cmd/Alt+Tab",
                    ),
                    Check(
                        "mods",
                        "Left/right Shift, Ctrl, Alt/Option and Cmd/Win each show as their own key and as modifiers",
                    ),
                    Check(
                        "dead",
                        "A dead key (´ then e on a US-International / French layout) types é, seen as a composition then one commit",
                    ),
                    Check(
                        "hold",
                        "macOS press-and-hold on 'e' opens the accent picker; choosing 2 replaces the e with è (no 'eè')",
                    ),
                    Check(
                        "ime",
                        "A CJK IME (Pinyin, Japanese) shows marked text, the candidate window sits at the caret, and Enter commits",
                    ),
                    Check("precomposed", "Accented letters land precomposed (é = U+00E9), not as e + U+0301"),
                ),
            keywords = listOf("keys", "ime", "dead keys", "accents", "composition", "press and hold"),
        )

    @Composable
    override fun Content() {
        val vm = metroViewModel<KeyboardViewModel>()
        val state by vm.state.collectAsState()
        val field = rememberTextFieldState()
        LaunchedEffect(field) {
            // Every edit, selection move and composition change, as the field holds it.
            snapshotFlow {
                TextSnapshot(
                    field.text.toString(),
                    field.selection.min..field.selection.max,
                    field.composition?.let { c -> c.start until c.end },
                )
            }.drop(1).collect(vm::onText)
        }

        ProbeLayout(
            capabilities = emptyList(),
            controls = {
                Hint("Click the capture area, then press keys.")
                KeyCapture(vm)
                Hint("Type in the field below with dead keys, press-and-hold and an IME.")
                // The field is the specimen: its IME and composition handling is under test.
                TextArea(
                    field,
                    modifier = Modifier.fillMaxWidth().onPreviewKeyEvent { vm.record(it) },
                    placeholder = "IME field",
                )
                Actions { SecondaryAction("Reset") { vm.onIntent(KeyboardIntent.Reset) } }
            },
            observed = {
                Counters(listOf("down" to state.downs, "up" to state.ups, "repeat" to state.repeats))
                Readout(
                    "Held",
                    state.held.values
                        .joinToString()
                        .ifEmpty { "none" },
                    tone = if (state.held.isNotEmpty()) Tone.Warning else Tone.Ok,
                )
                with(state.text) {
                    Readout("Text", "'$text' (${text.length} UTF-16 units)")
                    Readout("Code points", codePoints(text).ifEmpty { "—" })
                    Readout("Selection", "$selection")
                    Readout(
                        "Composition",
                        composition?.toString() ?: "none",
                        tone = if (composition != null) Tone.Warning else Tone.Neutral,
                    )
                }
                Readout("Composition updates", "${state.compositionUpdates}")
                SubHeading("IME commits")
                EventLog(state.commits.map(::LogEntry), newestFirst = false, empty = "No commit yet.")
                SubHeading("Key events (newest first)")
                EventLog(state.keyLines.map(::LogEntry), newestFirst = false, max = 30)
            },
        )
    }

    companion object {
        val ID = ProbeId("input.keyboard")
    }
}

@Composable
private fun KeyCapture(vm: KeyboardViewModel) {
    val focus = remember { FocusRequester() }
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    TargetArea(
        Modifier
            .height(96.dp)
            .focusRequester(focus)
            .onPreviewKeyEvent { vm.record(it) }
            .focusable(interactionSource = interaction)
            .clickable(interactionSource = interaction, indication = null) { focus.requestFocus() },
        highlighted = focused,
        label = if (focused) "Capturing keys — press anything" else "Click to capture keys",
    )
}

/** Observes without consuming; KeyEventType.Unknown (typed-only events) is skipped. */
private fun KeyboardViewModel.record(event: KeyEvent): Boolean {
    if (event.type == KeyEventType.KeyDown || event.type == KeyEventType.KeyUp) onKey(event.toRecord())
    return false
}

private fun KeyEvent.toRecord(): KeyRecord =
    KeyRecord(
        down = type == KeyEventType.KeyDown,
        key = key.toString().removePrefix("Key: "),
        nativeCode = key.keyCode,
        codePoint = utf16CodePoint,
        modifiers =
            buildList {
                if (isCtrlPressed) add("Ctrl")
                if (isAltPressed) add("Alt")
                if (isShiftPressed) add("Shift")
                if (isMetaPressed) add("Meta")
            }.joinToString("+").ifEmpty { "—" },
    )
