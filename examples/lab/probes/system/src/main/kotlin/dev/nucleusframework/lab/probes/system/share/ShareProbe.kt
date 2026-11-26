package dev.nucleusframework.lab.probes.system.share

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import dev.nucleusframework.application.LocalNucleusWindow
import dev.nucleusframework.application.shareAnchor
import dev.nucleusframework.core.runtime.Platform
import dev.nucleusframework.lab.core.Capability
import dev.nucleusframework.lab.core.Check
import dev.nucleusframework.lab.core.Domain
import dev.nucleusframework.lab.core.Probe
import dev.nucleusframework.lab.core.ProbeDescriptor
import dev.nucleusframework.lab.core.ProbeId
import dev.nucleusframework.lab.designsystem.Actions
import dev.nucleusframework.lab.designsystem.ChoiceRow
import dev.nucleusframework.lab.designsystem.EventLog
import dev.nucleusframework.lab.designsystem.Hint
import dev.nucleusframework.lab.designsystem.LogEntry
import dev.nucleusframework.lab.designsystem.ProbeLayout
import dev.nucleusframework.lab.designsystem.Readout
import dev.nucleusframework.lab.designsystem.SecondaryAction
import dev.nucleusframework.lab.designsystem.SubHeading
import dev.nucleusframework.lab.designsystem.Tone
import dev.nucleusframework.share.ShareAnchor
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metrox.viewmodel.metroViewModel

@ContributesIntoSet(AppScope::class)
@Inject
class ShareProbe : Probe {
    override val descriptor =
        ProbeDescriptor(
            id = ID,
            title = "Share sheet",
            domain = Domain.System,
            summary =
                "Does every payload reach the system share UI, parented to the Lab " +
                    "window, and is every malformed one rejected?",
            modules = listOf("share", "nucleus-application"),
            checks =
                listOf(
                    Check(
                        "parented",
                        "With Lab window, the sheet / portal dialog belongs to the Lab (macOS: points at the pressed button)",
                    ),
                    Check(
                        "payloads",
                        "Text, link, file and mixed payloads show the matching targets, the file keeps its name",
                    ),
                    Check("unicode-name", "The file with spaces and accents arrives under that exact name"),
                    Check(
                        "rejected",
                        "Blank, empty, bad URL and content:// are rejected with the expected ShareError, no UI shown",
                    ),
                    Check("missing-file", "A missing file fails with an error instead of opening an empty sheet"),
                    Check("auto-parent", "With Auto, the sheet still appears over the Lab (frontmost window fallback)"),
                ),
            keywords = listOf("share", "NSSharingServicePicker", "DataTransferManager", "portal"),
        )

    @Composable
    override fun Content() {
        val vm = metroViewModel<ShareViewModel>()
        val state by vm.state.collectAsState()
        val window = LocalNucleusWindow.current

        ProbeLayout(
            capabilities =
                listOf(
                    Capability("Native share UI", state.supported, detail = platformUi()),
                ),
            controls = {
                ChoiceRow("Parent", ShareParentChoice.entries, state.parent, name = ::parentName) {
                    vm.onIntent(ShareIntent.SetParent(it))
                }
                SubHeading("Presentable payloads")
                PayloadButtons(
                    SharePayload.entries.filter { it.expectation == Expectation.Presented },
                ) { payload, anchor ->
                    vm.onIntent(ShareIntent.Share(payload, window, anchor))
                }
                SubHeading("Must be rejected")
                PayloadButtons(
                    SharePayload.entries.filter { it.expectation != Expectation.Presented },
                ) { payload, anchor ->
                    vm.onIntent(ShareIntent.Share(payload, window, anchor))
                }
                Readout("files from", state.scratchDir, tone = Tone.Muted)
            },
            observed = {
                Hint(
                    "share() returns once the UI is up; it never says which target was picked " +
                        "or whether you cancelled.",
                )
                EventLog(state.attempts.map { it.toLogEntry() }, empty = "Nothing shared yet.")
            },
        )
    }

    /** One button per payload, each remembering its own bounds as the macOS anchor. */
    @Composable
    private fun PayloadButtons(
        payloads: List<SharePayload>,
        onShare: (SharePayload, ShareAnchor?) -> Unit,
    ) {
        val density = LocalDensity.current
        Actions {
            payloads.forEach { payload ->
                var anchor by remember { mutableStateOf<ShareAnchor?>(null) }
                Box(Modifier.onGloballyPositioned { anchor = it.shareAnchor(density) }) {
                    SecondaryAction(payload.label) { onShare(payload, anchor) }
                }
            }
        }
    }

    companion object {
        val ID = ProbeId("system.share")
    }
}

private fun parentName(parent: ShareParentChoice): String =
    when (parent) {
        ShareParentChoice.LabWindow -> "Lab window"
        ShareParentChoice.Auto -> "Auto"
    }

private fun ShareAttempt.toLogEntry(): LogEntry {
    val expected = payload.expectation
    val result =
        when (outcome) {
            null -> "in progress…"
            ShareOutcome.Presented -> "presented in $millis ms"
            is ShareOutcome.Failed -> "${outcome.error ?: "unexpected exception"}: ${outcome.message}"
        }
    val met = outcome?.let(expected::isMetBy)
    return LogEntry(
        text =
            "#$id ${payload.label} via $parent  → $result" +
                if (met == false) "  (expected ${expected.describe()})" else "",
        epochMillis = epochMillis,
        tone =
            when (met) {
                null -> Tone.Muted
                true -> Tone.Ok
                false -> Tone.Error
            },
    )
}

private fun platformUi(): String =
    when (Platform.Current) {
        Platform.MacOS -> "NSSharingServicePicker"
        Platform.Windows -> "DataTransferManager Share UI"
        Platform.Linux -> "XDG desktop portal (OpenURI / FileChooser), xdg-open fallback"
        else -> "none"
    }
