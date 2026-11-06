package dev.nucleusframework.lab.core

import dev.nucleusframework.core.runtime.Platform
import dev.nucleusframework.lab.core.checks.CheckResult
import dev.nucleusframework.lab.core.checks.CheckStatus
import dev.nucleusframework.lab.core.checks.buildReport
import dev.nucleusframework.lab.core.environment.EnvironmentSnapshot
import dev.nucleusframework.lab.core.timeline.EntryKind
import dev.nucleusframework.lab.core.timeline.TimelineEntry
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse

class ReportTest {
    private val environment =
        EnvironmentSnapshot(
            platform = Platform.Linux,
            osName = "Linux",
            osVersion = "6.9",
            arch = "amd64",
            displayServer = "Wayland",
            desktop = "Gnome",
            javaVersion = "25",
            javaVendor = "Temurin",
            executableType = "DEB",
            nativeImage = false,
            sandboxed = false,
            aotMode = "OFF",
            appId = "dev.nucleusframework.lab",
            appVersion = "1.0.0",
            monitors = emptyList(),
            nucleusProperties = mapOf("nucleus.tao.partialRedraw" to "true"),
            jvmArguments = emptyList(),
        )

    private val probe =
        ProbeDescriptor(
            id = ProbeId("shell.badge"),
            title = "Badge",
            domain = Domain.Shell,
            summary = "",
            modules = listOf("launcher-linux"),
            checks = listOf(Check("visible", "Badge is visible"), Check("cleared", "Clear removes it")),
        )

    private fun entry(
        source: ProbeId?,
        message: String,
        onUi: Boolean = true,
    ) = TimelineEntry(1, 0, source, EntryKind.Event, message, "worker-1", onUi)

    @Test
    fun `report carries environment, check marks and notes`() {
        val report =
            buildReport(
                environment = environment,
                probes = listOf(probe),
                results = { mapOf("visible" to CheckResult(CheckStatus.Fail, note = "only after restart")) },
                timeline = emptyList(),
            )
        assertContains(report, "Linux 6.9 (amd64)")
        assertContains(report, "Wayland · Gnome")
        assertContains(report, "`nucleus.tao.partialRedraw=true`")
        assertContains(report, "- [ ] ❌ Badge is visible — only after restart")
        assertContains(report, "- [ ] Clear removes it")
    }

    @Test
    fun `timeline is limited to the reported probes and flags off-UI threads`() {
        val report =
            buildReport(
                environment = environment,
                probes = listOf(probe),
                results = { emptyMap() },
                timeline =
                    listOf(
                        entry(probe.id, "count=5", onUi = false),
                        entry(ProbeId("other.probe"), "unrelated"),
                    ),
            )
        assertContains(report, "[worker-1]  count=5")
        assertFalse("unrelated" in report)
    }
}
