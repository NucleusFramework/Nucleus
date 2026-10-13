package dev.nucleusframework.lab.probes.input.pointer

import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.nucleusframework.lab.core.Check
import dev.nucleusframework.lab.core.Domain
import dev.nucleusframework.lab.core.Probe
import dev.nucleusframework.lab.core.ProbeDescriptor
import dev.nucleusframework.lab.core.ProbeId
import dev.nucleusframework.lab.core.time.monotonicMillis
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
import dev.nucleusframework.lab.designsystem.Tone
import dev.nucleusframework.lab.probes.input.common.describe
import dev.nucleusframework.lab.probes.input.common.fmt
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metrox.viewmodel.metroViewModel

@ContributesIntoSet(AppScope::class)
@Inject
class PointerProbe : Probe {
    override val descriptor =
        ProbeDescriptor(
            id = ID,
            title = "Pointer & hover",
            domain = Domain.Input,
            summary = "Do enter/exit, buttons, multi-clicks and cursor shapes behave like a native app?",
            modules = listOf("decorated-window-tao"),
            checks =
                listOf(
                    Check(
                        "hover",
                        "Moving in and out of the target pairs Enter/Exit; 'Phantom exits' stays 0, even while a popup or tooltip opens over it",
                    ),
                    Check("buttons", "Primary, secondary, middle, back and forward each register as their own button"),
                    Check(
                        "multi",
                        "A fast double / triple click reads ×2 / ×3; slow clicks or clicks far apart stay ×1",
                    ),
                    Check("mods", "Holding Ctrl/Alt/Shift/Cmd while clicking shows the right modifiers"),
                    Check(
                        "cursors",
                        "Every cursor tile shows its native cursor shape and the arrow comes back on leave",
                    ),
                    Check("outside", "Pressing inside then releasing outside the window still delivers the Release"),
                ),
            keywords = listOf("mouse", "hover", "cursor", "click", "double click"),
        )

    @Composable
    override fun Content() {
        val vm = metroViewModel<PointerViewModel>()
        val state by vm.state.collectAsState()

        ProbeLayout(
            capabilities = emptyList(),
            controls = {
                Hint("Hover, click, double-click and press every button in the area below.")
                HoverTarget(vm, hovered = state.hovered)
                Actions { SecondaryAction("Reset") { vm.onIntent(PointerIntent.Reset) } }
                SubHeading("Cursor shapes")
                Hint("Hover each tile: the native cursor must change, and the arrow come back on leave.")
                CursorGrid()
            },
            observed = {
                Readout("Position", state.position?.fmt(1) ?: "outside")
                Readout("Pointer type", state.pointerType)
                Readout("Buttons down", state.buttons)
                Readout("Modifiers", state.modifiers)
                Counters(
                    listOf(
                        "enter" to state.enters,
                        "exit" to state.exits,
                        "move" to state.moves,
                        "release" to state.releases,
                    ),
                )
                Readout(
                    "Phantom exits",
                    "${state.phantomExits}",
                    tone = if (state.phantomExits > 0) Tone.Error else Tone.Ok,
                )
                Readout(
                    "Presses",
                    state.presses.entries
                        .joinToString { "${it.key}=${it.value}" }
                        .ifEmpty { "none" },
                )
                Readout("Click count", "last ×${state.lastClickCount} · max ×${state.maxClickCount}")
                SubHeading("Events (moves only counted)")
                EventLog(state.lines.map(::LogEntry), newestFirst = false, max = 30)
            },
        )
    }

    companion object {
        val ID = ProbeId("input.pointer")
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun HoverTarget(
    vm: PointerViewModel,
    hovered: Boolean,
) {
    TargetArea(
        Modifier
            .height(220.dp)
            .pointerInput(vm) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull() ?: continue
                        val kind =
                            when (event.type) {
                                PointerEventType.Enter -> PointerKind.Enter
                                PointerEventType.Exit -> PointerKind.Exit
                                PointerEventType.Press -> PointerKind.Press
                                PointerEventType.Release -> PointerKind.Release
                                PointerEventType.Move -> PointerKind.Move
                                else -> continue
                            }
                        vm.onSample(
                            PointerSample(
                                kind = kind,
                                nowMs = monotonicMillis(),
                                position = change.position,
                                inside = change.position.isWellInside(size),
                                buttons = event.buttons.describe(),
                                button = event.button?.label(),
                                modifiers = event.keyboardModifiers.describe(),
                                pointerType = change.type.toString(),
                            ),
                        )
                    }
                }
            },
        highlighted = hovered,
        label = if (hovered) "Pointer inside" else "Hover me",
    )
}

/** Inside by more than 2 px: an exit reported there cannot be the pointer crossing the edge. */
private fun Offset.isWellInside(size: IntSize): Boolean =
    x > 2f && y > 2f && x < size.width - 2f && y < size.height - 2f

private fun PointerButton.label(): String =
    when (this) {
        PointerButton.Primary -> "primary"
        PointerButton.Secondary -> "secondary"
        PointerButton.Tertiary -> "tertiary"
        PointerButton.Back -> "back"
        PointerButton.Forward -> "forward"
        else -> "button#$index"
    }
