package dev.nucleusframework.lab.probes.lifecycle.autolaunch

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import dev.nucleusframework.autolaunch.AutoLaunchResult
import dev.nucleusframework.autolaunch.AutoLaunchState
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
import dev.nucleusframework.lab.designsystem.Hint
import dev.nucleusframework.lab.designsystem.LogEntry
import dev.nucleusframework.lab.designsystem.PrimaryAction
import dev.nucleusframework.lab.designsystem.ProbeLayout
import dev.nucleusframework.lab.designsystem.Readout
import dev.nucleusframework.lab.designsystem.SecondaryAction
import dev.nucleusframework.lab.designsystem.SubHeading
import dev.nucleusframework.lab.designsystem.Tone
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metrox.viewmodel.metroViewModel

@ContributesIntoSet(AppScope::class)
@Inject
class AutoLaunchProbe : Probe {
    override val descriptor =
        ProbeDescriptor(
            id = ID,
            title = "Start at login",
            domain = Domain.Lifecycle,
            summary =
                "Does enabling start-at-login register with the OS mechanism, and does a login launch know " +
                    "it was one?",
            modules = listOf("autolaunch", "service-management-macos"),
            checks =
                listOf(
                    Check(
                        "enable",
                        "Enable returns OK and the app appears in the OS list (Login Items / Task Manager Startup " +
                            "/ systemd --user)",
                    ),
                    Check("disable", "Disable returns OK and the entry disappears from the OS list"),
                    Check(
                        "user-lock",
                        "Turning it off in the OS UI shows DISABLED_BY_USER here within 3 s; Enable then returns " +
                            "BLOCKED_BY_USER",
                    ),
                    Check("settings", "Open system settings lands on the startup-apps page"),
                    Check(
                        "login",
                        "After a logout/login with it enabled, the Lab starts and \"Started at login\" reads true",
                    ),
                    Check("manual", "Launched by hand, \"Started at login\" reads false"),
                ),
            keywords = listOf("autostart", "login item", "startup", "SMAppService", "Run key", "systemd"),
        )

    @Composable
    override fun Content() {
        val vm = metroViewModel<AutoLaunchViewModel>()
        val state by vm.state.collectAsState()
        val supported = state.state != null && state.state != AutoLaunchState.UNSUPPORTED

        ProbeLayout(
            capabilities =
                listOf(
                    Capability(
                        "Backend ${state.backend.orEmpty()}",
                        if (state.state == null) {
                            Availability.Unknown
                        } else {
                            Availability.of(supported) { "UNSUPPORTED — see diagnostic" }
                        },
                    ),
                    Capability(
                        "Login-launch detection",
                        Availability.of(
                            state.executableType != "DEV",
                        ) { "dev run: wasStartedAtLogin() is always false" },
                    ),
                ),
            controls = {
                Actions {
                    PrimaryAction("Enable", enabled = supported) { vm.onIntent(AutoLaunchIntent.Enable) }
                    SecondaryAction("Disable", enabled = supported) { vm.onIntent(AutoLaunchIntent.Disable) }
                    SecondaryAction("Open system settings") { vm.onIntent(AutoLaunchIntent.OpenSettings) }
                    SecondaryAction("Refresh") { vm.onIntent(AutoLaunchIntent.Refresh) }
                }
                Hint("State is re-read every 3 s, so changes made in the OS show up on their own.")
            },
            observed = {
                Readout("state()", state.state?.name, tone = state.state.tone())
                Readout("Started at login", state.startedAtLogin?.toString())
                Readout("Autostart marker", state.autostartArgument ?: "none")
                state.settingsOpened?.let {
                    Readout(
                        "openSystemSettings()",
                        it.toString(),
                        tone = if (it) Tone.Ok else Tone.Error,
                    )
                }
                SubHeading("Requests")
                EventLog(state.attempts.map { it.toLogEntry() })
                SubHeading("State changes")
                EventLog(state.stateChanges.map { (at, value) -> LogEntry(value.name, at, tone = value.tone()) })
                SubHeading("diagnostic()")
                CodeBlock(state.diagnostic.trim())
            },
        )
    }

    companion object {
        val ID = ProbeId("lifecycle.autolaunch")
    }
}

private fun AutoLaunchState?.tone(): Tone =
    when (this) {
        AutoLaunchState.ENABLED, AutoLaunchState.ENABLED_BY_POLICY -> Tone.Ok
        AutoLaunchState.DISABLED_BY_USER, AutoLaunchState.DISABLED_BY_POLICY -> Tone.Warning
        AutoLaunchState.UNSUPPORTED -> Tone.Error
        else -> Tone.Neutral
    }

private fun AutoLaunchAttempt.toLogEntry(): LogEntry =
    LogEntry(
        text = "$action → " + (error ?: "$result → $stateAfter"),
        epochMillis = epochMillis,
        tone = result.tone(error),
    )

private fun AutoLaunchResult?.tone(error: String?): Tone =
    when {
        error != null || this == AutoLaunchResult.ERROR -> Tone.Error
        this == AutoLaunchResult.OK || this == AutoLaunchResult.UNCHANGED -> Tone.Ok
        else -> Tone.Warning
    }
