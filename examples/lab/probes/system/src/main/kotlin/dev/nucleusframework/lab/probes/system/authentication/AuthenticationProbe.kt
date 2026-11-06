package dev.nucleusframework.lab.probes.system.authentication

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import dev.nucleusframework.application.LocalNucleusWindow
import dev.nucleusframework.authentication.AuthenticationAvailability
import dev.nucleusframework.authentication.AuthenticationPolicy
import dev.nucleusframework.core.runtime.Platform
import dev.nucleusframework.lab.core.Availability
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
import dev.nucleusframework.lab.designsystem.SecondaryAction
import dev.nucleusframework.lab.designsystem.TextFieldRow
import dev.nucleusframework.lab.designsystem.Tone
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metrox.viewmodel.metroViewModel

@ContributesIntoSet(AppScope::class)
@Inject
class AuthenticationProbe : Probe {
    override val descriptor =
        ProbeDescriptor(
            id = ID,
            title = "Authentication",
            domain = Domain.System,
            summary =
                "Does the system prompt confirm the device owner, report every refusal as the " +
                    "right AuthenticationError, and go away when the app cancels it?",
            modules = listOf("authentication", "nucleus-application"),
            checks =
                listOf(
                    Check(
                        "availability",
                        "Availability matches the machine (Touch ID / Hello / polkit) for both policies, without any UI",
                    ),
                    Check("success", "Authenticating (finger, PIN or password) ends with Succeeded"),
                    Check("user-cancel", "The prompt's Cancel button ends with UserCanceled"),
                    Check("app-cancel", "Lab cancels after 2 s: the prompt disappears and the attempt ends Cancelled"),
                    Check(
                        "biometrics-only",
                        "Biometrics: macOS offers no password fallback; Windows and Linux end with UnsupportedPolicy",
                    ),
                    Check("blank-reason", "A blank reason fails with InvalidPrompt and shows nothing"),
                    Check(
                        "parented",
                        "Windows, with Lab window: the Hello dialog is modal to the Lab and in front of it",
                    ),
                ),
            keywords = listOf("authentication", "Touch ID", "Windows Hello", "polkit", "LAContext", "biometric"),
        )

    @Composable
    override fun Content() {
        val vm = metroViewModel<AuthenticationViewModel>()
        val state by vm.state.collectAsState()
        val window = LocalNucleusWindow.current

        ProbeLayout(
            capabilities =
                buildList {
                    add(Capability("Native prompt", state.supported, detail = platformUi()))
                    AuthenticationPolicy.entries.forEach { policy ->
                        val availability = state.availability[policy] ?: return@forEach
                        add(Capability(policy.name, availability.toAvailability(), availability.detail()))
                    }
                },
            controls = {
                ChoiceRow("Policy", AuthenticationPolicy.entries, state.policy, name = { it.name }) {
                    vm.onIntent(AuthIntent.SetPolicy(it))
                }
                ChoiceRow("Parent", AuthParentChoice.entries, state.parent, name = ::parentName) {
                    vm.onIntent(AuthIntent.SetParent(it))
                }
                TextFieldRow("Reason", state.reason) { vm.onIntent(AuthIntent.SetReason(it)) }
                if (Platform.Current == Platform.Linux) {
                    TextFieldRow("polkit action", state.linuxActionId) { vm.onIntent(AuthIntent.SetLinuxAction(it)) }
                }
                Actions {
                    AuthMode.entries.forEach { mode ->
                        SecondaryAction(mode.label) { vm.onIntent(AuthIntent.Authenticate(mode, window)) }
                    }
                    SecondaryAction("Refresh availability") { vm.onIntent(AuthIntent.Refresh) }
                }
            },
            observed = {
                Hint(
                    "authenticate() returns once the prompt is answered. Success unlocks nothing " +
                        "cryptographically: it is a gate, not a key.",
                )
                EventLog(state.attempts.map { it.toLogEntry() }, empty = "No attempt yet.")
            },
        )
    }

    companion object {
        val ID = ProbeId("system.authentication")
    }
}

private fun AuthenticationAvailability.toAvailability(): Availability =
    if (isAvailable) Availability.Available else Availability.Unavailable("$error${message?.let { ": $it" } ?: ""}")

private fun AuthenticationAvailability.detail(): String = "biometry: $biometry"

private fun parentName(parent: AuthParentChoice): String =
    when (parent) {
        AuthParentChoice.LabWindow -> "Lab window"
        AuthParentChoice.Auto -> "Auto"
    }

private fun AuthAttempt.toLogEntry(): LogEntry {
    val result =
        when (outcome) {
            null -> "waiting for the prompt…"
            AuthOutcome.Succeeded -> "succeeded in $millis ms"
            AuthOutcome.Cancelled -> "cancelled by the Lab after $millis ms"
            is AuthOutcome.Failed -> "${outcome.error ?: "unexpected exception"}: ${outcome.message}"
        }
    val met = outcome?.let(mode::isMetBy)
    return LogEntry(
        text =
            "#$id ${mode.label}, $policy via $parent  → $result" +
                if (met == false) "  (expected ${mode.expected()})" else "",
        epochMillis = epochMillis,
        tone =
            when {
                outcome == null -> Tone.Muted
                met == false -> Tone.Error
                met == true || outcome == AuthOutcome.Succeeded -> Tone.Ok
                else -> Tone.Neutral
            },
    )
}

private fun platformUi(): String =
    when (Platform.Current) {
        Platform.MacOS -> "LocalAuthentication (Touch ID, Apple Watch, password)"
        Platform.Windows -> "Windows Hello, CredUI password fallback"
        Platform.Linux -> "polkit agent"
        else -> "none"
    }
