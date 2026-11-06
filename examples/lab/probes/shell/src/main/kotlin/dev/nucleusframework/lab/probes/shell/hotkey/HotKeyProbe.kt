package dev.nucleusframework.lab.probes.shell.hotkey

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
import dev.nucleusframework.lab.designsystem.EmptyState
import dev.nucleusframework.lab.designsystem.EventLog
import dev.nucleusframework.lab.designsystem.LabeledRow
import dev.nucleusframework.lab.designsystem.ProbeLayout
import dev.nucleusframework.lab.designsystem.Readout
import dev.nucleusframework.lab.designsystem.SecondaryAction
import dev.nucleusframework.lab.designsystem.SubHeading
import dev.nucleusframework.lab.designsystem.TertiaryAction
import dev.nucleusframework.lab.designsystem.Tone
import dev.nucleusframework.lab.designsystem.toLogEntry
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metrox.viewmodel.metroViewModel

@ContributesIntoSet(AppScope::class)
@Inject
class HotKeyProbe : Probe {
    override val descriptor =
        ProbeDescriptor(
            id = ID,
            title = "Global hotkeys",
            domain = Domain.Shell,
            summary =
                "Do system-wide shortcuts fire while another app has focus, and are refusals " +
                    "reported instead of swallowed?",
            modules = listOf("global-hotkey"),
            checks =
                listOf(
                    Check(
                        "background",
                        "With another app focused, each registered combo is listed below on every press",
                    ),
                    Check("once", "One press = one line: no repeat while held, no double delivery"),
                    Check("thread", "Presses arrive on the UI thread (no ⚠ marker)"),
                    Check(
                        "refused",
                        "The reserved combo is refused with a readable error — or, if accepted, it is flagged",
                    ),
                    Check(
                        "duplicate",
                        "Registering the same combo twice fails cleanly or yields two handles that both fire",
                    ),
                    Check("unregister", "After Unregister, the combo goes back to the OS / other apps"),
                    Check(
                        "portal",
                        "Wayland: the portal dialog lists the Lab's descriptions, and Commit applies them at once",
                    ),
                ),
            keywords =
                listOf(
                    "shortcut",
                    "RegisterHotKey",
                    "Carbon",
                    "XGrabKey",
                    "GlobalShortcuts portal",
                    "media keys",
                ),
        )

    @Composable
    override fun Content() {
        val vm = metroViewModel<HotKeyViewModel>()
        val state by vm.state.collectAsState()
        val ready = state.availability.isAvailable && state.initialized?.ok == true

        ProbeLayout(
            capabilities =
                listOfNotNull(
                    Capability("Global hotkeys", state.availability, detail = state.backend),
                    state.initialized?.let {
                        Capability(
                            "initialize()",
                            if (it.ok) Availability.Available else Availability.Unavailable(it.error.orEmpty()),
                        )
                    },
                ),
            controls = {
                Combos.forEach { combo ->
                    LabeledRow(combo.label) {
                        SecondaryAction(
                            if (combo.expectFailure) "Register (should be refused)" else "Register",
                            enabled = ready,
                        ) {
                            vm.onIntent(HotKeyIntent.Register(combo))
                        }
                    }
                }
                Actions {
                    SecondaryAction("Unregister all", enabled = ready && state.registrations.isNotEmpty()) {
                        vm.onIntent(HotKeyIntent.UnregisterAll)
                    }
                    if (Platform.isWayland) {
                        SecondaryAction(
                            "Commit (portal)",
                            enabled = ready,
                        ) { vm.onIntent(HotKeyIntent.Commit) }
                    }
                }
            },
            observed = {
                SubHeading("Registered")
                if (state.registrations.isEmpty()) EmptyState("Nothing registered.")
                state.registrations.forEach { registration ->
                    Readout(
                        "#${registration.handle} ${registration.combo.label}",
                        "${registration.presses} press(es)" +
                            (registration.portalId?.let { " · portal id $it" } ?: ""),
                        tone = if (registration.combo.expectFailure) Tone.Warning else Tone.Neutral,
                    )
                    Actions {
                        TertiaryAction(
                            "Unregister #${registration.handle}",
                        ) { vm.onIntent(HotKeyIntent.Unregister(registration.handle)) }
                    }
                }
                SubHeading("Presses")
                EventLog(
                    state.presses.map { it.toLogEntry() },
                    empty = "Focus another app, then press a registered combo.",
                )
                SubHeading("Calls")
                EventLog(state.calls.map { it.toLogEntry() }, empty = "No call made yet.")
            },
        )
    }

    companion object {
        val ID = ProbeId("shell.global-hotkey")
    }
}
