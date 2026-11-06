package dev.nucleusframework.lab.probes.lifecycle.deeplink

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
import dev.nucleusframework.lab.core.commands.ReceivedDeepLink
import dev.nucleusframework.lab.designsystem.Actions
import dev.nucleusframework.lab.designsystem.ChoiceRow
import dev.nucleusframework.lab.designsystem.EventLog
import dev.nucleusframework.lab.designsystem.LogEntry
import dev.nucleusframework.lab.designsystem.PrimaryAction
import dev.nucleusframework.lab.designsystem.ProbeLayout
import dev.nucleusframework.lab.designsystem.Readout
import dev.nucleusframework.lab.designsystem.SecondaryAction
import dev.nucleusframework.lab.designsystem.SubHeading
import dev.nucleusframework.lab.designsystem.TextFieldRow
import dev.nucleusframework.lab.designsystem.Tone
import dev.nucleusframework.lab.probes.lifecycle.launch.LaunchList
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metrox.viewmodel.metroViewModel

@ContributesIntoSet(AppScope::class)
@Inject
class DeepLinkProbe : Probe {
    override val descriptor =
        ProbeDescriptor(
            id = ID,
            title = "Deep links",
            domain = Domain.Lifecycle,
            summary = "Does a nucleus-lab:// link reach onDeepLink — in process, from a second launch, from the OS?",
            modules = listOf("core-runtime", "nucleus-application"),
            checks =
                listOf(
                    Check(
                        "in-process",
                        "In process: \"Echo back here\" lands in Received and its params show under Params for " +
                            "this probe",
                    ),
                    Check(
                        "second-dev",
                        "Dev run, second instance: a second Lab window opens on the linked probe (no " +
                            "single-instance lock)",
                    ),
                    Check(
                        "second-packaged",
                        "Installed app, second instance: the child exits 0 within a second and the link appears " +
                            "here; this window comes to front",
                    ),
                    Check(
                        "os",
                        "Installed app, through the OS: the link opens this Lab (no second window) and appears in " +
                            "Received",
                    ),
                    Check(
                        "cold",
                        "With the Lab closed, opening a link from a browser launches it straight onto the linked probe",
                    ),
                    Check("encoding", "\"Encoded params\" arrives decoded: text=café & crème"),
                ),
            keywords = listOf("url scheme", "protocol", "uri", "onDeepLink"),
        )

    @Composable
    override fun Content() {
        val vm = metroViewModel<DeepLinkViewModel>()
        val state by vm.state.collectAsState()

        ProbeLayout(
            capabilities =
                listOf(
                    Capability(
                        "Scheme ${state.scheme}://",
                        Availability.of(
                            state.singleInstanceActive,
                        ) { "dev run: no installed app registers the scheme with the OS" },
                        detail =
                            "Registered at install time from nativeDistributions { protocol(\"NucleusLab\", " +
                                "\"nucleus-lab\") }",
                    ),
                    Capability(
                        "Single-instance forwarding",
                        Availability.of(
                            state.singleInstanceActive,
                        ) { "nucleusApplication skips the lock in ${state.executableType} runs" },
                    ),
                ),
            controls = {
                TextFieldRow("Link", state.draft) { vm.onIntent(DeepLinkIntent.EditDraft(it)) }
                ChoiceRow("Deliver", LinkRoute.entries, state.route, name = { it.label }) {
                    vm.onIntent(DeepLinkIntent.SetRoute(it))
                }
                Actions {
                    PrimaryAction("Send") { vm.onIntent(DeepLinkIntent.Send) }
                    if (state.launches.any { it.running }) {
                        SecondaryAction("Kill launched instances") { vm.onIntent(DeepLinkIntent.KillLaunched) }
                    }
                }
                SubHeading("Suggested links")
                Actions {
                    state.suggestions.forEach { link ->
                        SecondaryAction(link.label) { vm.onIntent(DeepLinkIntent.EditDraft(link.uri)) }
                    }
                }
            },
            observed = {
                SubHeading("Received by onDeepLink · ${state.received.size}")
                EventLog(state.received.map { it.toLogEntry() }, max = RECEIVED_SHOWN)
                SubHeading("Params for this probe")
                EventLog(
                    state.paramsReceived.map { params -> LogEntry(params.entries.joinToString { (k, v) -> "$k=$v" }) },
                    max = PARAMS_SHOWN,
                    empty = "None — send a link to ${ID.value}.",
                )
                state.osResult?.let { Readout("OS handoff", it, tone = Tone.Ok) }
                state.deliveryError?.let { Readout("Delivery failed", it, tone = Tone.Error) }
                LaunchList(state.launches)
            },
        )
    }

    companion object {
        val ID = ProbeId("lifecycle.deep-links")
        private const val RECEIVED_SHOWN = 12
        private const val PARAMS_SHOWN = 6
    }
}

private fun ReceivedDeepLink.toLogEntry(): LogEntry = LogEntry(uri.toString(), epochMillis, detail = "($thread)")
