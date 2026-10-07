package dev.nucleusframework.lab.core.checks

import dev.nucleusframework.lab.core.ProbeDescriptor
import dev.nucleusframework.lab.core.environment.EnvironmentSnapshot
import dev.nucleusframework.lab.core.format.formatTime
import dev.nucleusframework.lab.core.timeline.TimelineEntry

/**
 * Markdown ready to paste into an issue: environment, the check sheet of [probes], and the
 * last timeline entries of those probes.
 */
fun buildReport(
    environment: EnvironmentSnapshot,
    probes: List<ProbeDescriptor>,
    results: (ProbeDescriptor) -> Map<String, CheckResult>,
    timeline: List<TimelineEntry>,
    timelineTail: Int = 60,
): String =
    buildString {
        appendLine("## Environment")
        appendLine()
        appendLine("| | |")
        appendLine("|---|---|")
        appendLine("| OS | ${environment.osName} ${environment.osVersion} (${environment.arch}) |")
        environment.displayServer?.let {
            appendLine(
                "| Display | $it${environment.desktop?.let { d ->
                    " · $d"
                }.orEmpty()} |",
            )
        }
        appendLine("| JDK | ${environment.javaVersion} (${environment.javaVendor}) |")
        appendLine("| Executable | ${environment.executableType}${if (environment.sandboxed) " · sandboxed" else ""} |")
        appendLine("| AOT | ${environment.aotMode} |")
        appendLine("| App | ${environment.appId} ${environment.appVersion.orEmpty()} |")
        if (environment.monitors.isNotEmpty()) {
            val monitors = environment.monitors.joinToString { "${it.widthPx}×${it.heightPx}@${it.scale}x" }
            appendLine("| Monitors | $monitors |")
        }
        if (environment.nucleusProperties.isNotEmpty()) {
            appendLine(
                "| Flags | ${environment.nucleusProperties.entries.joinToString { "`${it.key}=${it.value}`" }} |",
            )
        }

        for (probe in probes) {
            appendLine()
            appendLine("## ${probe.title} (`${probe.id}`)")
            appendLine()
            appendLine("Modules: ${probe.modules.joinToString { "`$it`" }}")
            val sheet = results(probe)
            if (probe.checks.isNotEmpty()) {
                appendLine()
                for (check in probe.checks) {
                    val result = sheet[check.id] ?: CheckResult()
                    val mark =
                        when (result.status) {
                            CheckStatus.Pass -> "[x] ✅"
                            CheckStatus.Fail -> "[ ] ❌"
                            CheckStatus.Skipped -> "[ ] ⏭"
                            CheckStatus.Untested -> "[ ]"
                        }
                    append("- $mark ${check.description}")
                    if (result.note.isNotBlank()) append(" — ${result.note}")
                    appendLine()
                }
            }
        }

        val ids = probes.map { it.id }.toSet()
        val entries = timeline.filter { it.source == null || it.source in ids }.takeLast(timelineTail)
        if (entries.isNotEmpty()) {
            appendLine()
            appendLine("<details><summary>Timeline (last ${entries.size})</summary>")
            appendLine()
            appendLine("```")
            entries.forEach { entry ->
                val thread = if (entry.onUiThread == false) " [${entry.thread}]" else ""
                appendLine(
                    "${formatTime(
                        entry.epochMillis,
                    )} ${entry.kind.name.padEnd(7)} ${entry.source ?: "lab"}$thread  ${entry.message}",
                )
            }
            appendLine("```")
            appendLine("</details>")
        }
    }
