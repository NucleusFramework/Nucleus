package dev.nucleusframework.lab.probes.input.dnd

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import dev.nucleusframework.lab.core.Check
import dev.nucleusframework.lab.core.Domain
import dev.nucleusframework.lab.core.Probe
import dev.nucleusframework.lab.core.ProbeDescriptor
import dev.nucleusframework.lab.core.ProbeId
import dev.nucleusframework.lab.designsystem.Actions
import dev.nucleusframework.lab.designsystem.Counters
import dev.nucleusframework.lab.designsystem.EmptyState
import dev.nucleusframework.lab.designsystem.EventLog
import dev.nucleusframework.lab.designsystem.Hint
import dev.nucleusframework.lab.designsystem.LogEntry
import dev.nucleusframework.lab.designsystem.ProbeLayout
import dev.nucleusframework.lab.designsystem.Readout
import dev.nucleusframework.lab.designsystem.SecondaryAction
import dev.nucleusframework.lab.designsystem.SubHeading
import dev.nucleusframework.lab.designsystem.Tone
import dev.nucleusframework.window.tao.TaoDnDDiagnostics
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metrox.viewmodel.metroViewModel

@ContributesIntoSet(AppScope::class)
@Inject
class DndProbe : Probe {
    override val descriptor =
        ProbeDescriptor(
            id = ID,
            title = "Drag & drop",
            domain = Domain.Input,
            summary = "Do drags go in and out of the app with every payload type, through the native drag session?",
            modules = listOf("decorated-window-tao"),
            checks =
                listOf(
                    Check("in-files", "Dropping files from Finder / Explorer / Files lists their absolute paths"),
                    Check(
                        "in-text",
                        "Dropping selected text or a link from a browser shows the text (and the URI for a link)",
                    ),
                    Check(
                        "out-text",
                        "Dragging 'Drag text' into a text editor inserts hello-from-nucleus-lab; the export reports Copy",
                    ),
                    Check("out-file", "Dragging 'Drag file' onto the desktop or a folder copies the temp file"),
                    Check(
                        "internal",
                        "Dragging a source onto the drop zone of this window works and reports Entered → drop",
                    ),
                    Check(
                        "feedback",
                        "The drop zone highlights while hovered and clears on leave or cancel (Esc during the drag)",
                    ),
                ),
            keywords = listOf("dnd", "drag and drop", "files", "drop target"),
        )

    @Composable
    override fun Content() {
        val vm = metroViewModel<DndViewModel>()
        val state by vm.state.collectAsState()
        val diag = TaoDnDDiagnostics

        ProbeLayout(
            capabilities = emptyList(),
            controls = {
                Hint("Drag a source out of the Lab, or onto the zone below.")
                SubHeading("Sources")
                DragSources(vm)
                SubHeading("Drop zone")
                DropZone(vm, state.hovering)
                Actions { SecondaryAction("Reset") { vm.onIntent(DndIntent.Reset) } }
            },
            observed = {
                Counters(DragPhase.entries.map { it.name to (state.phases[it] ?: 0) } + ("drops" to state.dropCount))
                SubHeading("Native session (TaoDnDDiagnostics)")
                Counters(
                    listOf(
                        "manager" to diag.constructed.intValue,
                        "isRequired" to diag.isRequiredQueries.intValue,
                        "requests" to diag.requests.intValue,
                        "transfers" to diag.transfers.intValue,
                    ),
                )
                Readout("Last native message", diag.lastMessage.value)
                SubHeading("Drops (newest first)")
                if (state.drops.isEmpty()) EmptyState("No drop yet.")
                state.drops.forEach { drop ->
                    Readout("#${drop.index} types", drop.mimeTypes.joinToString().ifEmpty { "none" })
                    if (drop.files.isNotEmpty()) Readout("files", drop.files.joinToString("\n"))
                    drop.text?.let { Readout("text", it.take(200)) }
                    if (drop.uris.isNotEmpty()) Readout("uris", drop.uris.joinToString("\n"))
                    drop.error?.let { Readout("error", it, tone = Tone.Error) }
                }
                SubHeading("Exports")
                EventLog(state.exports.map(::LogEntry), newestFirst = false, empty = "Nothing dragged out yet.")
            },
        )
    }

    companion object {
        val ID = ProbeId("input.dnd")
    }
}
