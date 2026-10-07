package dev.nucleusframework.lab.probes.updater.engine

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
import dev.nucleusframework.lab.core.format.formatBytes
import dev.nucleusframework.lab.core.format.percent
import dev.nucleusframework.lab.designsystem.Actions
import dev.nucleusframework.lab.designsystem.ChoiceRow
import dev.nucleusframework.lab.designsystem.EmptyState
import dev.nucleusframework.lab.designsystem.Hint
import dev.nucleusframework.lab.designsystem.Meter
import dev.nucleusframework.lab.designsystem.PrimaryAction
import dev.nucleusframework.lab.designsystem.ProbeLayout
import dev.nucleusframework.lab.designsystem.Readout
import dev.nucleusframework.lab.designsystem.SecondaryAction
import dev.nucleusframework.lab.designsystem.SubHeading
import dev.nucleusframework.lab.designsystem.SwitchRow
import dev.nucleusframework.lab.designsystem.TextFieldRow
import dev.nucleusframework.lab.designsystem.Tone
import dev.nucleusframework.updater.UpdateSimulation
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metrox.viewmodel.metroViewModel

@ContributesIntoSet(AppScope::class)
@Inject
class UpdaterProbe : Probe {
    override val descriptor =
        ProbeDescriptor(
            id = ID,
            title = "Updater",
            domain = Domain.Updater,
            summary =
                "Does the real update engine check, download, verify and hand over — from a simulation, a " +
                    "test feed, a folder or GitHub?",
            modules = listOf("updater-runtime"),
            checks =
                listOf(
                    Check(
                        "sim",
                        "Simulation UPDATE_AVAILABLE: check finds the next minor, download progresses over ~6 s to " +
                            "100 %",
                    ),
                    Check(
                        "sim-errors",
                        "Simulation CHECK_ERROR / DOWNLOAD_ERROR / CHECKSUM_ERROR each end in a readable error, " +
                            "never a hang",
                    ),
                    Check(
                        "feed",
                        "Fault-injecting feed: check finds the published version with the right level; download " +
                            "matches its size",
                    ),
                    Check("dev-install", "Dev run: Install is refused with the reason, nothing is launched"),
                    Check(
                        "just-updated",
                        "Simulation with justUpdatedFrom=1.0.0: Consume event reports 1.0.0 → current, then nothing",
                    ),
                    Check("github", "GitHub in a dev run: NotAvailable — no redirect, by design"),
                    Check(
                        "installed",
                        "Installed app + test feed + armed install: the installer runs and the new version starts",
                    ),
                ),
            keywords = listOf("NucleusUpdater", "auto update", "download", "sha512", "differential", "simulation"),
        )

    @Composable
    override fun Content() {
        val vm = metroViewModel<UpdaterViewModel>()
        val state by vm.state.collectAsState()
        val form = state.form
        val facts = state.facts

        fun edit(change: SetupForm.() -> SetupForm) = vm.onIntent(UpdaterIntent.Edit(form.change()))

        ProbeLayout(
            capabilities =
                listOfNotNull(
                    facts?.let {
                        Capability(
                            "isUpdateSupported()",
                            Availability.of(it.updateSupported) {
                                "dev run without a feed redirect, or a store build"
                            },
                        )
                    },
                    facts?.feedOverride?.let { Capability("Feed redirect", Availability.Available, detail = it) },
                ),
            controls = {
                ChoiceRow("Source", SourceKind.entries, form.kind, name = { it.label }) { edit { copy(kind = it) } }
                when (form.kind) {
                    SourceKind.Simulation -> {
                        ChoiceRow(
                            "Scenario",
                            UpdateSimulation.Scenario.entries,
                            form.scenario,
                        ) { edit { copy(scenario = it) } }
                        TextFieldRow("Just updated from", form.justUpdatedFrom) { edit { copy(justUpdatedFrom = it) } }
                    }
                    SourceKind.Directory ->
                        TextFieldRow(
                            "Directory with latest*.yml",
                            form.directory,
                        ) { edit { copy(directory = it) } }
                    SourceKind.GitHub -> TextFieldRow("owner/repo", form.gitHubRepo) { edit { copy(gitHubRepo = it) } }
                    SourceKind.FeedServer -> Hint("Start and publish in the Fault-injecting feed probe first.")
                }
                TextFieldRow(
                    "Pretend current version (blank = real)",
                    form.currentVersion,
                ) { edit { copy(currentVersion = it) } }
                TextFieldRow("Channel", form.channel) { edit { copy(channel = it) } }
                SwitchRow("Allow prerelease", form.allowPrerelease) { edit { copy(allowPrerelease = it) } }
                SwitchRow("Differential download", form.differential) { edit { copy(differential = it) } }
                Actions {
                    SecondaryAction("Apply") { vm.onIntent(UpdaterIntent.Apply) }
                    PrimaryAction(if (state.checking) "Checking…" else "Check", enabled = !state.checking) {
                        vm.onIntent(UpdaterIntent.Check)
                    }
                    SecondaryAction(
                        "Download",
                        enabled =
                            state.outcome is CheckOutcome.Available && state.download?.running != true,
                    ) {
                        vm.onIntent(UpdaterIntent.Download)
                    }
                }
                SwitchRow(
                    "Arm install (replaces this app)",
                    state.armInstall,
                ) { vm.onIntent(UpdaterIntent.ArmInstall(it)) }
                Actions {
                    SecondaryAction(
                        "Install and restart",
                        enabled = state.download?.file != null,
                    ) { vm.onIntent(UpdaterIntent.Install) }
                    SecondaryAction("Consume update event") { vm.onIntent(UpdaterIntent.ConsumeEvent) }
                }
            },
            observed = { Observed(state) },
        )
    }

    companion object {
        val ID = ProbeId("updater.engine")
    }
}

@Composable
private fun Observed(state: UpdaterState) {
    state.configError?.let { Readout("Configuration", it, tone = Tone.Error) }
    state.facts?.let {
        Readout("currentVersion", it.currentVersion)
        Readout("simulation", it.simulation ?: "none")
        Readout("wasJustUpdated()", it.wasJustUpdated.toString())
    }
    Readout("At launch", state.launchEvent)
    state.consumedEvent?.let { Readout("consumeUpdateEvent()", it) }
    Readout("pendingRestartVersion", state.pendingRestart ?: "none (Windows hot update only)")
    SubHeading("Check")
    when (val outcome = state.outcome) {
        null -> EmptyState(if (state.checking) "Checking…" else "Not checked.")
        CheckOutcome.NotAvailable -> Readout("Result", "NotAvailable")
        is CheckOutcome.Failed -> {
            Readout("Result", "${outcome.type}: ${outcome.message}", tone = Tone.Error)
            outcome.cause?.let { Readout("Cause", it, tone = Tone.Error) }
        }
        is CheckOutcome.Available -> {
            Readout("Result", "Available ${outcome.info.version} (${outcome.level})", tone = Tone.Ok)
            Readout("Released", outcome.info.releaseDate)
            val file = outcome.info.currentFile
            Readout("File for this OS", "${file.fileName} · ${formatBytes(file.size)}")
            Readout("sha512", file.sha512.take(SHA_PREFIX) + "…")
            Readout("Block map", file.blockMapSize?.let(::formatBytes) ?: "none")
        }
    }
    state.download?.let { download ->
        SubHeading("Download")
        val fraction = download.percent / 100
        Meter(
            "Progress",
            fraction.toFloat(),
            "${percent(fraction)} · ${formatBytes(download.bytes)} / ${formatBytes(download.total)}",
            tone = if (download.error != null) Tone.Error else null,
        )
        Readout("Speed", "${formatBytes(download.bytesPerSecond(System.currentTimeMillis()))}/s")
        Readout("Differential", download.differential.toString())
        download.file?.let { Readout("Verified file", it, tone = Tone.Ok) }
        download.error?.let { Readout("Failed", it, tone = Tone.Error) }
    }
    state.install?.let { Readout("Install", it, tone = if (it.startsWith("refused")) Tone.Warning else Tone.Neutral) }
}

private const val SHA_PREFIX = 24
