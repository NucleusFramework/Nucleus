package dev.nucleusframework.lab.probes.input.clipboard

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalClipboard
import dev.nucleusframework.core.runtime.Platform
import dev.nucleusframework.lab.core.Availability
import dev.nucleusframework.lab.core.Capability
import dev.nucleusframework.lab.core.Check
import dev.nucleusframework.lab.core.Domain
import dev.nucleusframework.lab.core.Probe
import dev.nucleusframework.lab.core.ProbeDescriptor
import dev.nucleusframework.lab.core.ProbeId
import dev.nucleusframework.lab.designsystem.Actions
import dev.nucleusframework.lab.designsystem.EmptyState
import dev.nucleusframework.lab.designsystem.EventLog
import dev.nucleusframework.lab.designsystem.LogEntry
import dev.nucleusframework.lab.designsystem.PrimaryAction
import dev.nucleusframework.lab.designsystem.ProbeLayout
import dev.nucleusframework.lab.designsystem.Readout
import dev.nucleusframework.lab.designsystem.SecondaryAction
import dev.nucleusframework.lab.designsystem.SubHeading
import dev.nucleusframework.lab.designsystem.SwitchRow
import dev.nucleusframework.lab.designsystem.TextFieldRow
import dev.nucleusframework.lab.designsystem.Tone
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metrox.viewmodel.metroViewModel

@ContributesIntoSet(AppScope::class)
@Inject
class ClipboardProbe : Probe {
    override val descriptor =
        ProbeDescriptor(
            id = ID,
            title = "Clipboard",
            domain = Domain.Input,
            summary = "Do text, rich text, images and files round-trip through the system clipboard, both ways?",
            modules = listOf("decorated-window-tao"),
            checks =
                listOf(
                    Check("text-out", "'Copy text' then paste in another app gives exactly the draft"),
                    Check("rich-out", "'Copy HTML' pastes bold + italic in a rich editor and plain text in a terminal"),
                    Check("image-out", "'Copy image' pastes a 64×32 gradient into an image editor"),
                    Check("files-out", "'Copy file' pastes the temp file into Finder / Explorer / Files"),
                    Check(
                        "in",
                        "Copying text, an image or files in another app shows up while watching ('Changed outside' increments)",
                    ),
                    Check(
                        "wayland",
                        "On Wayland, copy/paste with native apps works in both directions (GTK clipboard, #582)",
                    ),
                ),
            keywords = listOf("copy", "paste", "clipboard", "html", "image"),
        )

    @Composable
    override fun Content() {
        val vm = metroViewModel<ClipboardViewModel>()
        val state by vm.state.collectAsState()
        val clipboard = LocalClipboard.current
        DisposableEffect(clipboard) {
            vm.bind(ComposeClipboardPort(clipboard))
            onDispose { vm.bind(null) }
        }

        ProbeLayout(
            capabilities =
                listOf(
                    Capability(
                        "Clipboard backend",
                        if (state.bound) Availability.Available else Availability.Unknown,
                        detail =
                            if (Platform.Current == Platform.Linux) {
                                "GTK when available (Wayland-aware), AWT otherwise"
                            } else {
                                "AWT system clipboard"
                            },
                    ),
                ),
            controls = {
                TextFieldRow("Draft", state.draft) { vm.onDraft(it) }
                Actions {
                    PrimaryAction("Copy text") { vm.onIntent(ClipboardIntent.Write(Payload.Text)) }
                    SecondaryAction("Copy HTML") { vm.onIntent(ClipboardIntent.Write(Payload.Html)) }
                    SecondaryAction("Copy image") { vm.onIntent(ClipboardIntent.Write(Payload.Image)) }
                    SecondaryAction("Copy file") { vm.onIntent(ClipboardIntent.Write(Payload.Files)) }
                }
                Actions { SecondaryAction("Read clipboard") { vm.onIntent(ClipboardIntent.Read) } }
                SwitchRow("Watch (1 s poll)", state.watching) { vm.onIntent(ClipboardIntent.Watch(it)) }
            },
            observed = {
                val content = state.content
                Readout("Changed outside", "${state.externalChanges}")
                Readout("Last written", state.lastWritten?.name)
                if (content == null) {
                    EmptyState("Clipboard not read yet.")
                } else {
                    Readout("Flavors", content.mimeTypes.joinToString().ifEmpty { "empty" })
                    Readout("Text", content.text?.take(300))
                    content.html?.let { Readout("HTML", it.take(300)) }
                    if (content.files.isNotEmpty()) Readout("Files", content.files.joinToString("\n"))
                    content.imageSize?.let { Readout("Image", it) }
                    if (content.errors.isNotEmpty()) {
                        Readout(
                            "Errors",
                            content.errors.joinToString("\n"),
                            tone = Tone.Error,
                        )
                    }
                }
                SubHeading("History")
                EventLog(state.history.map(::LogEntry), newestFirst = false)
            },
        )
    }

    companion object {
        val ID = ProbeId("input.clipboard")
    }
}
