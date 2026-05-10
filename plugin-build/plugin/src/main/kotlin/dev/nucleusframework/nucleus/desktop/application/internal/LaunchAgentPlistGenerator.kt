package dev.nucleusframework.nucleus.desktop.application.internal

import dev.nucleusframework.nucleus.desktop.application.dsl.CalendarInterval
import dev.nucleusframework.nucleus.desktop.application.dsl.LaunchAgentDefinition
import java.io.File

/**
 * Generates macOS launchd agent plist files from [LaunchAgentDefinition] DSL objects.
 */
internal object LaunchAgentPlistGenerator {

    fun generate(agent: LaunchAgentDefinition, destDir: File, packageName: String) {
        destDir.mkdirs()
        val resolvedBundleProgram = agent.bundleProgram ?: "Contents/MacOS/$packageName"
        val plist = buildPlistXml(agent, resolvedBundleProgram)
        File(destDir, agent.plistFileName).writeText(plist)
    }

    @Suppress("NestedBlockDepth")
    private fun buildPlistXml(agent: LaunchAgentDefinition, resolvedBundleProgram: String): String = buildString {
        appendLine("""<?xml version="1.0" encoding="UTF-8"?>""")
        appendLine(
            "<!DOCTYPE plist PUBLIC \"-//Apple//DTD PLIST 1.0//EN\" " +
                "\"http://www.apple.com/DTDs/PropertyList-1.0.dtd\">",
        )
        appendLine("""<plist version="1.0">""")
        appendLine("<dict>")

        plistKey("Label", agent.label)

        plistKey("BundleProgram", resolvedBundleProgram)

        // ProgramArguments — always include bundle program as first arg
        val allArgs = buildList {
            add(resolvedBundleProgram)
            addAll(agent.programArguments)
        }
        if (allArgs.isNotEmpty()) {
            appendLine("  <key>ProgramArguments</key>")
            appendLine("  <array>")
            for (arg in allArgs) {
                appendLine("    <string>$arg</string>")
            }
            appendLine("  </array>")
        }

        agent.startInterval?.let { plistKey("StartInterval", it) }

        if (agent.calendarIntervals.isNotEmpty()) {
            appendLine("  <key>StartCalendarInterval</key>")
            if (agent.calendarIntervals.size == 1) {
                appendCalendarDict(agent.calendarIntervals[0], indent = "  ")
            } else {
                appendLine("  <array>")
                for (interval in agent.calendarIntervals) {
                    appendCalendarDict(interval, indent = "    ")
                }
                appendLine("  </array>")
            }
        }

        if (agent.runAtLoad) plistKey("RunAtLoad", true)
        if (agent.keepAlive) plistKey("KeepAlive", true)

        plistKey("ProcessType", agent.processType)

        appendLine("</dict>")
        appendLine("</plist>")
    }

    private fun StringBuilder.plistKey(key: String, value: String) {
        appendLine("  <key>$key</key>")
        appendLine("  <string>$value</string>")
    }

    private fun StringBuilder.plistKey(key: String, value: Int) {
        appendLine("  <key>$key</key>")
        appendLine("  <integer>$value</integer>")
    }

    private fun StringBuilder.plistKey(key: String, value: Boolean) {
        appendLine("  <key>$key</key>")
        appendLine("  <${if (value) "true" else "false"}/>")
    }

    private fun StringBuilder.appendCalendarDict(interval: CalendarInterval, indent: String) {
        appendLine("$indent<dict>")
        interval.month?.let {
            appendLine("$indent  <key>Month</key>")
            appendLine("$indent  <integer>$it</integer>")
        }
        interval.day?.let {
            appendLine("$indent  <key>Day</key>")
            appendLine("$indent  <integer>$it</integer>")
        }
        interval.weekday?.let {
            appendLine("$indent  <key>Weekday</key>")
            appendLine("$indent  <integer>$it</integer>")
        }
        interval.hour?.let {
            appendLine("$indent  <key>Hour</key>")
            appendLine("$indent  <integer>$it</integer>")
        }
        interval.minute?.let {
            appendLine("$indent  <key>Minute</key>")
            appendLine("$indent  <integer>$it</integer>")
        }
        appendLine("$indent</dict>")
    }
}
