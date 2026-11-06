package dev.nucleusframework.lab.probes.updater.feed

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import dev.nucleusframework.lab.core.Availability
import dev.nucleusframework.lab.core.Capability
import dev.nucleusframework.lab.core.Check
import dev.nucleusframework.lab.core.Domain
import dev.nucleusframework.lab.core.Probe
import dev.nucleusframework.lab.core.ProbeDescriptor
import dev.nucleusframework.lab.core.ProbeId
import dev.nucleusframework.lab.core.format.fmt
import dev.nucleusframework.lab.core.format.formatBytes
import dev.nucleusframework.lab.designsystem.Actions
import dev.nucleusframework.lab.designsystem.ChoiceRow
import dev.nucleusframework.lab.designsystem.CodeBlock
import dev.nucleusframework.lab.designsystem.EmptyState
import dev.nucleusframework.lab.designsystem.EventLog
import dev.nucleusframework.lab.designsystem.LogEntry
import dev.nucleusframework.lab.designsystem.PrimaryAction
import dev.nucleusframework.lab.designsystem.ProbeLayout
import dev.nucleusframework.lab.designsystem.Readout
import dev.nucleusframework.lab.designsystem.SecondaryAction
import dev.nucleusframework.lab.designsystem.SliderRow
import dev.nucleusframework.lab.designsystem.SubHeading
import dev.nucleusframework.lab.designsystem.TextFieldRow
import dev.nucleusframework.lab.designsystem.Tone
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metrox.viewmodel.metroViewModel

@ContributesIntoSet(AppScope::class)
@Inject
class FeedServerProbe : Probe {
    override val descriptor =
        ProbeDescriptor(
            id = ID,
            title = "Fault-injecting feed",
            domain = Domain.Updater,
            summary = "A loopback release host that misbehaves on demand: does the updater survive what real hosts do?",
            modules = listOf("updater-testing"),
            checks =
                listOf(
                    Check("serve", "Start + publish: the manifest below lists the artifact with its sha512 and size"),
                    Check(
                        "throttle",
                        "Throttle the artifact: the Updater probe's progress advances smoothly at ~512 KB/s",
                    ),
                    Check(
                        "truncate",
                        "Cut at half once: the download fails with a readable error, the next attempt succeeds",
                    ),
                    Check(
                        "corrupt",
                        "Corrupt the artifact: the download is rejected by the SHA-512 check, nothing is kept",
                    ),
                    Check("503", "HTTP 503 on the manifest: the check returns an error, not \"no update\""),
                    Check("log", "Every request the updater makes shows in the request log with its status and bytes"),
                ),
            keywords = listOf("UpdateFeedServer", "FeedFault", "latest.yml", "throttle", "truncate"),
        )

    @Composable
    override fun Content() {
        val vm = metroViewModel<FeedServerViewModel>()
        val state by vm.state.collectAsState()
        val status = state.status

        ProbeLayout(
            capabilities =
                listOf(
                    Capability(
                        "Loopback server",
                        Availability.of(status.running) { "not started" },
                        detail = status.baseUrl,
                    ),
                ),
            controls = {
                Actions {
                    if (status.running) {
                        SecondaryAction("Stop server") { vm.onIntent(FeedServerIntent.Stop) }
                    } else {
                        PrimaryAction("Start server") { vm.onIntent(FeedServerIntent.Start) }
                    }
                }
                SubHeading("Release")
                TextFieldRow("Version to publish", state.version) { vm.onIntent(FeedServerIntent.EditVersion(it)) }
                SliderRow("Artifact size", state.sizeMb, valueRange = 1f..128f, format = { "${it.fmt(0)} MiB" }) {
                    vm.onIntent(FeedServerIntent.SetSize(it))
                }
                Actions { PrimaryAction("Publish", enabled = status.running) { vm.onIntent(FeedServerIntent.Publish) } }
                SubHeading("Fault")
                ChoiceRow("Kind", FaultKind.entries, state.faultKind, name = { it.label }) {
                    vm.onIntent(FeedServerIntent.ConfigureFault(kind = it))
                }
                ChoiceRow("On", FaultTarget.entries, state.faultTarget, name = { it.label }) {
                    vm.onIntent(FeedServerIntent.ConfigureFault(target = it))
                }
                ChoiceRow("Times", FaultTimes.entries, state.faultTimes, name = { it.label }) {
                    vm.onIntent(FeedServerIntent.ConfigureFault(times = it))
                }
                Actions {
                    PrimaryAction("Inject", enabled = status.running) { vm.onIntent(FeedServerIntent.InjectFault) }
                    SecondaryAction(
                        "Clear faults",
                        enabled = status.running,
                    ) { vm.onIntent(FeedServerIntent.ClearFaults) }
                    SecondaryAction(
                        "Clear log",
                        enabled = status.running,
                    ) { vm.onIntent(FeedServerIntent.ClearRequests) }
                }
            },
            observed = {
                Readout("Base URL", status.baseUrl ?: "stopped", tone = if (status.running) Tone.Ok else Tone.Muted)
                Readout("Feed directory", status.directory)
                state.error?.let { Readout("Error", it, tone = Tone.Error) }
                SubHeading("Published")
                val published = status.published
                if (published == null) {
                    EmptyState("Nothing published.")
                } else {
                    Readout(published.version, "${published.artifact} · ${formatBytes(published.sizeBytes)}")
                    CodeBlock(published.manifest)
                }
                SubHeading("Armed faults")
                if (status.faults.isEmpty()) EmptyState("None.")
                status.faults.forEach { Readout(it.path, "${it.fault} × ${it.times ?: "∞"}", tone = Tone.Warning) }
                SubHeading("Request log · ${status.requests.size}")
                EventLog(
                    status.requests.map { LogEntry(it.line, tone = if (it.failed) Tone.Warning else Tone.Neutral) },
                    max = LOG_LINES,
                    empty = "No request yet.",
                )
            },
        )
    }

    companion object {
        val ID = ProbeId("updater.feed-server")
        private const val LOG_LINES = 30
    }
}
