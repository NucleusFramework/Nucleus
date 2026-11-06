package dev.nucleusframework.lab.probes.lifecycle.smappservice

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import dev.nucleusframework.core.runtime.Platform
import dev.nucleusframework.lab.core.Availability
import dev.nucleusframework.lab.core.Capability
import dev.nucleusframework.lab.core.Check
import dev.nucleusframework.lab.core.Domain
import dev.nucleusframework.lab.core.Probe
import dev.nucleusframework.lab.core.ProbeDescriptor
import dev.nucleusframework.lab.core.ProbeId
import dev.nucleusframework.lab.designsystem.Actions
import dev.nucleusframework.lab.designsystem.CodeBlock
import dev.nucleusframework.lab.designsystem.EventLog
import dev.nucleusframework.lab.designsystem.ProbeLayout
import dev.nucleusframework.lab.designsystem.Readout
import dev.nucleusframework.lab.designsystem.SecondaryAction
import dev.nucleusframework.lab.designsystem.SubHeading
import dev.nucleusframework.lab.designsystem.Tone
import dev.nucleusframework.lab.designsystem.toLogEntry
import dev.nucleusframework.servicemanagement.AppServiceStatus
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metrox.viewmodel.metroViewModel

@ContributesIntoSet(AppScope::class)
@Inject
class AppServiceProbe : Probe {
    override val descriptor =
        ProbeDescriptor(
            id = ID,
            title = "SMAppService",
            domain = Domain.Lifecycle,
            summary =
                "Do login items and launch agents register through SMAppService, and does the agent actually " +
                    "run?",
            modules = listOf("service-management-macos"),
            platforms = setOf(Platform.MacOS),
            checks =
                listOf(
                    Check(
                        "main-app",
                        "Register main app → ENABLED, and the Lab is listed under Login Items › Open at Login",
                    ),
                    Check(
                        "agent",
                        "Packaged: register the heartbeat agent → ENABLED or REQUIRES_APPROVAL, listed under Allow " +
                            "in the Background",
                    ),
                    Check(
                        "approval",
                        "Approving it in System Settings flips REQUIRES_APPROVAL to ENABLED here within 3 s",
                    ),
                    Check(
                        "heartbeat",
                        "With the agent enabled, a new heartbeat line appears about every minute, even with the " +
                            "Lab closed",
                    ),
                    Check("unregister", "Unregister completes without error and the status returns to NOT_REGISTERED"),
                    Check(
                        "not-found",
                        "The undeclared daemon reports NOT_FOUND and its register fails with a readable error",
                    ),
                ),
            keywords = listOf("login item", "launch agent", "launchd", "daemon", "background"),
        )

    @Composable
    override fun Content() {
        val vm = metroViewModel<AppServiceViewModel>()
        val state by vm.state.collectAsState()
        val available = state.available == true

        ProbeLayout(
            capabilities =
                listOf(
                    Capability(
                        "SMAppService",
                        when (state.available) {
                            null -> Availability.Unknown
                            true -> Availability.Available
                            false -> Availability.Unavailable("needs macOS 13+ and the native library")
                        },
                    ),
                ),
            controls = {
                LabService.entries.forEach { service ->
                    SubHeading(service.label)
                    Actions {
                        SecondaryAction(
                            "Register",
                            enabled = available,
                        ) { vm.onIntent(AppServiceIntent.Register(service)) }
                        SecondaryAction(
                            "Unregister",
                            enabled = available,
                        ) { vm.onIntent(AppServiceIntent.Unregister(service)) }
                    }
                }
                Actions {
                    SecondaryAction(
                        "Open Login Items",
                        enabled = available,
                    ) { vm.onIntent(AppServiceIntent.OpenLoginItems) }
                    SecondaryAction("Refresh") { vm.onIntent(AppServiceIntent.Refresh) }
                }
            },
            observed = {
                LabService.entries.forEach { service ->
                    val status = state.statuses[service]
                    Readout(service.label, status?.name ?: "—", tone = status.tone())
                    Readout("expected", service.expectation, tone = Tone.Muted, indent = 1)
                }
                state.loginItemsOpened?.let {
                    Readout(
                        "Login Items opened",
                        it.toString(),
                        tone = if (it) Tone.Ok else Tone.Error,
                    )
                }
                SubHeading("Results")
                EventLog(state.calls.map { it.toLogEntry() })
                SubHeading("Agent heartbeats (last 10)")
                CodeBlock(state.heartbeats.asReversed().joinToString("\n"), empty = "The agent has not run yet.")
            },
        )
    }

    companion object {
        val ID = ProbeId("lifecycle.smappservice")
    }
}

private fun AppServiceStatus?.tone(): Tone =
    when (this) {
        AppServiceStatus.ENABLED -> Tone.Ok
        AppServiceStatus.REQUIRES_APPROVAL -> Tone.Warning
        AppServiceStatus.NOT_FOUND -> Tone.Error
        else -> Tone.Neutral
    }
