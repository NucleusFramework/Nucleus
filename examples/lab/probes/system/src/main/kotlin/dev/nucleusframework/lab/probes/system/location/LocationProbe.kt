package dev.nucleusframework.lab.probes.system.location

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import dev.nucleusframework.application.LocalNucleusWindow
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
import dev.nucleusframework.lab.designsystem.PrimaryAction
import dev.nucleusframework.lab.designsystem.ProbeLayout
import dev.nucleusframework.lab.designsystem.Readout
import dev.nucleusframework.lab.designsystem.SecondaryAction
import dev.nucleusframework.lab.designsystem.SubHeading
import dev.nucleusframework.lab.designsystem.Tone
import dev.nucleusframework.location.LocationAccess
import dev.nucleusframework.location.LocationAccuracy
import dev.nucleusframework.location.LocationAuthorization
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metrox.viewmodel.metroViewModel

@ContributesIntoSet(AppScope::class)
@Inject
class LocationProbe : Probe {
    override val descriptor =
        ProbeDescriptor(
            id = ID,
            title = "Location",
            domain = Domain.System,
            summary =
                "Does the OS prompt for location, deliver a fix and live updates, and does a " +
                    "change made in the system settings show up without a request?",
            modules = listOf("location"),
            checks =
                listOf(
                    Check(
                        "prompt",
                        "Request shows the system prompt the first time (Windows 11 24H2+, macOS alert, " +
                            "Linux portal dialog) and reports the answer",
                    ),
                    Check("one-shot", "Fresh fix arrives within 30 s with a plausible position and accuracy"),
                    Check("cached", "Cached fix comes back at once, marked (cached)"),
                    Check("tracking", "Tracking delivers updates (~1 s on Windows) and stops when switched off"),
                    Check(
                        "revocation",
                        "Turning location off in the system settings flips Authorization within ~1 s, " +
                            "without pressing anything here",
                    ),
                    Check("settings", "Open settings shows the OS location privacy page"),
                    Check(
                        "parented",
                        "Linux: the portal consent dialog is attached to the Lab window (X11 and Wayland)",
                    ),
                ),
            keywords = listOf("location", "geolocation", "GPS", "Geolocator", "Core Location", "GeoClue", "portal"),
        )

    @Composable
    override fun Content() {
        val vm = metroViewModel<LocationViewModel>()
        val state by vm.state.collectAsState()
        val usable = state.available.isAvailable
        val window = LocalNucleusWindow.current

        ProbeLayout(
            capabilities = listOf(Capability("Location service", state.available, detail = backendName())),
            controls = {
                ChoiceRow("Accuracy", LocationAccuracy.entries, state.accuracy) {
                    vm.onIntent(LocationIntent.SetAccuracy(it))
                }
                SubHeading("Authorization")
                Actions {
                    PrimaryAction("Request foreground", enabled = usable) {
                        vm.onIntent(LocationIntent.RequestAuthorization(LocationAccess.Foreground, window))
                    }
                    SecondaryAction("Request background", enabled = usable) {
                        vm.onIntent(LocationIntent.RequestAuthorization(LocationAccess.Background, window))
                    }
                    SecondaryAction("Open settings") { vm.onIntent(LocationIntent.OpenSettings) }
                }
                SubHeading("Fixes")
                Actions {
                    PrimaryAction("Fresh fix", enabled = usable) {
                        vm.onIntent(LocationIntent.CurrentLocation(cached = false))
                    }
                    SecondaryAction("Cached fix", enabled = usable) {
                        vm.onIntent(LocationIntent.CurrentLocation(cached = true))
                    }
                    SecondaryAction(if (state.tracking) "Stop tracking" else "Start tracking", enabled = usable) {
                        vm.onIntent(LocationIntent.SetTracking(!state.tracking))
                    }
                    SecondaryAction("Clear") { vm.onIntent(LocationIntent.Clear) }
                }
            },
            observed = {
                Readout("authorization", state.authorization?.name ?: "…", tone = state.authorization.tone())
                Readout("last fix", state.lastFix?.describe() ?: "none yet")
                Readout("live updates", state.updates.toString())
                Hint(authorizationHint())
                EventLog(state.log.map { it.toLogEntry() }, empty = "Nothing requested yet.")
            },
        )
    }

    companion object {
        val ID = ProbeId("system.location")
    }
}

private fun LocationAuthorization?.tone(): Tone =
    when (this) {
        LocationAuthorization.Foreground, LocationAuthorization.Background -> Tone.Ok
        LocationAuthorization.Denied, LocationAuthorization.Restricted -> Tone.Error
        LocationAuthorization.NotDetermined, null -> Tone.Muted
    }

private fun LocationLine.toLogEntry(): LogEntry =
    LogEntry(
        text = text,
        epochMillis = epochMillis,
        tone =
            when (tone) {
                LocationTone.Info -> Tone.Neutral
                LocationTone.Ok -> Tone.Ok
                LocationTone.Failed -> Tone.Error
            },
    )

private fun backendName(): String =
    when (Platform.Current) {
        Platform.Windows -> "WinRT Geolocator"
        Platform.MacOS -> "Core Location"
        Platform.Linux -> "XDG Location portal, GeoClue 2 fallback"
        Platform.Unknown -> "none"
    }

private fun authorizationHint(): String =
    when (Platform.Current) {
        Platform.Windows ->
            "Windows cannot tell \"allowed\" from \"never asked\": NotDetermined until a request answers. " +
                "Turning location off in Settings reads as Denied."
        Platform.MacOS -> "Prompts only from a packaged .app with the usage keys and the location entitlement."
        Platform.Linux -> "The portal and GeoClue report no revocation: the value only changes on a request."
        Platform.Unknown -> ""
    }
