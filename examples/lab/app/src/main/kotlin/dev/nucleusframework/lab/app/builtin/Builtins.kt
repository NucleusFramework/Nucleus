package dev.nucleusframework.lab.app.builtin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.nucleusframework.lab.app.shell.LocalShell
import dev.nucleusframework.lab.app.shell.OverviewProbeId
import dev.nucleusframework.lab.app.shell.ShellIntent
import dev.nucleusframework.lab.app.shell.progress
import dev.nucleusframework.lab.core.Domain
import dev.nucleusframework.lab.core.Probe
import dev.nucleusframework.lab.core.ProbeDescriptor
import dev.nucleusframework.lab.core.ProbeId
import dev.nucleusframework.lab.designsystem.Actions
import dev.nucleusframework.lab.designsystem.LabDimens
import dev.nucleusframework.lab.designsystem.LabTheme
import dev.nucleusframework.lab.designsystem.Readout
import dev.nucleusframework.lab.designsystem.Readouts
import dev.nucleusframework.lab.designsystem.ScrollableColumn
import dev.nucleusframework.lab.designsystem.SecondaryAction
import dev.nucleusframework.lab.designsystem.Section
import dev.nucleusframework.lab.designsystem.SelectableRow
import dev.nucleusframework.lab.designsystem.StatusDot
import dev.nucleusframework.lab.designsystem.Text
import dev.nucleusframework.lab.designsystem.Tone
import dev.nucleusframework.lab.designsystem.color

/** The Lab's own screens; they read the shell instead of a ViewModel of their own. */
val BuiltinProbes: List<Probe> = listOf(OverviewProbe, EnvironmentProbe, CoverageProbe)

/**
 * Every runtime module of the repository (`settings.gradle.kts`), so a module without a
 * probe shows up instead of silently going untested.
 */
val RuntimeModules: List<String> =
    listOf(
        "core-runtime",
        "aot-runtime",
        "updater-runtime",
        "updater-testing",
        "darkmode-detector",
        "native-ssl",
        "native-http",
        "native-http-okhttp",
        "native-http-ktor",
        "linux-hidpi",
        "spellcheck",
        "system-color",
        "decorated-window-core",
        "decorated-window-tao",
        "nucleus-application",
        "decorated-window-jewel",
        "decorated-window-material2",
        "decorated-window-material3",
        "graalvm-runtime",
        "energy-manager",
        "taskbar-progress",
        "taskbar-progress-tao",
        "notification-macos",
        "service-management-macos",
        "notification-linux",
        "notification-windows",
        "notification-common",
        "launcher-windows",
        "launcher-linux",
        "global-hotkey",
        "media-control",
        "launcher-macos",
        "menu-macos",
        "freedesktop-icons",
        "sf-symbols",
        "system-info",
        "autolaunch",
        "scheduler",
        "scheduler-testing",
        "fs-watcher",
        "share",
    )

private object OverviewProbe : Probe {
    override val descriptor =
        ProbeDescriptor(
            id = OverviewProbeId,
            title = "Overview",
            domain = Domain.Lab,
            summary = "Where this run stands: every probe's check sheet in the current environment.",
            modules = emptyList(),
        )

    @Composable
    override fun Content() {
        val shell = LocalShell.current
        val state by shell.state.collectAsState()
        val fingerprint = state.environment?.fingerprint
        val probes = shell.probes.filter { it.descriptor.domain != Domain.Lab }
        val progress = probes.associate { it.descriptor.id to state.book.progress(fingerprint, it.descriptor) }
        val total = progress.values.sumOf { it.total }
        val decided = progress.values.sumOf { it.decided }
        val failed = progress.values.sumOf { it.failed }

        ScrollableColumn(
            Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(LabDimens.page),
            verticalArrangement = Arrangement.spacedBy(LabDimens.blockGap),
        ) {
            Section("This run") {
                Readout("Environment", state.environment?.summary)
                Readout("Fingerprint", fingerprint)
                Readout(
                    "Checks",
                    "$decided / $total decided · $failed failed",
                    tone = if (failed > 0) Tone.Error else Tone.Neutral,
                )
                Readout(
                    "Probes",
                    "${probes.size} (${probes.count { it.descriptor.supportsCurrentPlatform }} runnable here)",
                )
                Actions {
                    SecondaryAction("Copy full report") { shell.onIntent(ShellIntent.CopyReport(null)) }
                    SecondaryAction("Open palette") { shell.onIntent(ShellIntent.SetPalette(true)) }
                }
            }
            probes.groupBy { it.descriptor.domain }.forEach { (domain, inDomain) ->
                Section(domain.title) {
                    inDomain.forEach { probe ->
                        val p = progress.getValue(probe.descriptor.id)
                        val supported = probe.descriptor.supportsCurrentPlatform
                        SelectableRow(
                            selected = false,
                            onClick = { shell.onIntent(ShellIntent.Select(probe.descriptor.id)) },
                        ) {
                            StatusDot(if (supported) p.tone else Tone.Muted)
                            Text(
                                probe.descriptor.title,
                                modifier = Modifier.width(220.dp),
                            )
                            Text(
                                if (supported) p.summary else "not on this OS",
                                style = LabTheme.typography.mono,
                                color = if (supported) p.tone.color() else Tone.Muted.color(),
                            )
                        }
                    }
                }
            }
        }
    }
}

private object EnvironmentProbe : Probe {
    override val descriptor =
        ProbeDescriptor(
            id = ProbeId("lab.environment"),
            title = "Environment",
            domain = Domain.Lab,
            summary = "Everything a bug report needs about this process, read live.",
            modules = listOf("core-runtime", "aot-runtime", "decorated-window-tao"),
        )

    @Composable
    override fun Content() {
        val shell = LocalShell.current
        val state by shell.state.collectAsState()
        val env = state.environment ?: return
        ScrollableColumn(
            Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(LabDimens.page),
            verticalArrangement = Arrangement.spacedBy(LabDimens.blockGap),
        ) {
            Section("Process", trailing = { SecondaryAction("Refresh") { shell.refreshEnvironment() } }) {
                Readouts(
                    listOf(
                        "OS" to "${env.osName} ${env.osVersion}",
                        "Arch" to env.arch,
                        "Display server" to env.displayServer,
                        "Desktop" to env.desktop,
                        "JDK" to "${env.javaVersion} (${env.javaVendor})",
                        "Executable type" to env.executableType,
                        "Native image" to env.nativeImage.toString(),
                        "Sandboxed" to env.sandboxed.toString(),
                        "AOT mode" to env.aotMode,
                        "App id" to env.appId,
                        "App version" to env.appVersion,
                        "Fingerprint" to env.fingerprint,
                    ),
                )
            }
            Section("Monitors") {
                if (env.monitors.isEmpty()) Readout("", "not enumerated yet", tone = Tone.Muted)
                env.monitors.forEach { m ->
                    Readout(m.name + if (m.primary) " (primary)" else "", "${m.widthPx}×${m.heightPx} px @ ${m.scale}x")
                }
            }
            Section("nucleus.* properties") {
                if (env.nucleusProperties.isEmpty()) Readout("", "none set", tone = Tone.Muted)
                env.nucleusProperties.forEach { (k, v) -> Readout(k, v) }
            }
            Section("JVM arguments") {
                env.jvmArguments.forEach { Text(it, style = LabTheme.typography.mono) }
            }
        }
    }
}

private object CoverageProbe : Probe {
    override val descriptor =
        ProbeDescriptor(
            id = ProbeId("lab.coverage"),
            title = "Coverage",
            domain = Domain.Lab,
            summary = "Which runtime modules have a probe, and which go untested.",
            modules = emptyList(),
        )

    @Composable
    override fun Content() {
        val shell = LocalShell.current
        val byModule: Map<String, List<ProbeDescriptor>> =
            shell.probes
                .map { it.descriptor }
                .flatMap { d -> d.modules.map { it to d } }
                .groupBy({ it.first }, { it.second })
        val uncovered = RuntimeModules.filter { it !in byModule }
        ScrollableColumn(
            Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(LabDimens.page),
            verticalArrangement = Arrangement.spacedBy(LabDimens.blockGap),
        ) {
            Section("Untested modules · ${uncovered.size}") {
                if (uncovered.isEmpty()) Readout("", "every runtime module has a probe", tone = Tone.Ok)
                uncovered.forEach { Readout(":$it", "no probe", tone = Tone.Error) }
            }
            Section("Covered · ${RuntimeModules.size - uncovered.size}") {
                RuntimeModules.filter { it in byModule }.forEach { module ->
                    Readout(":$module", byModule.getValue(module).joinToString { it.title })
                }
            }
        }
    }
}
