package dev.nucleusframework.nucleus.desktop.application.dsl

import org.gradle.api.Action
import java.io.Serializable

/**
 * DSL block for declaring macOS launch agents to embed in the app bundle
 * at `Contents/Library/LaunchAgents/`.
 *
 * These agents are registered at runtime via `SMAppService.agent(plistName:)`.
 *
 * ```kotlin
 * macOS {
 *     launchAgents {
 *         agent("com.myapp.sync") {
 *             bundleProgram("Contents/MacOS/MyApp")
 *             arguments("--sync")
 *             startInterval(900) // every 15 minutes
 *         }
 *     }
 * }
 * ```
 */
class LaunchAgentSettings : Serializable {
    internal val agents: MutableList<LaunchAgentDefinition> = mutableListOf()

    /**
     * Declares a launch agent with the given label.
     *
     * @param label unique reverse-DNS identifier (e.g. `"com.myapp.background-sync"`)
     */
    fun agent(label: String, fn: Action<LaunchAgentDefinition>) {
        val definition = LaunchAgentDefinition(label)
        fn.execute(definition)
        agents.add(definition)
    }

    companion object {
        private const val serialVersionUID = 1L
    }
}

/**
 * Defines a single launch agent plist to embed in the app bundle.
 */
class LaunchAgentDefinition(
    /** Unique reverse-DNS label for the agent (maps to `Label` plist key). */
    val label: String,
) : Serializable {
    internal var bundleProgram: String? = null
    internal val programArguments: MutableList<String> = mutableListOf()
    internal var startInterval: Int? = null
    internal var runAtLoad: Boolean = false
    internal var keepAlive: Boolean = false
    internal val calendarIntervals: MutableList<CalendarInterval> = mutableListOf()
    internal var processType: String = "Background"

    /**
     * Path to the executable relative to the app bundle root.
     *
     * Example: `"Contents/MacOS/MyApp"`
     */
    fun bundleProgram(path: String) {
        bundleProgram = path
    }

    /**
     * Additional arguments passed to the program.
     * The bundle program path is automatically prepended.
     */
    fun arguments(vararg args: String) {
        programArguments.addAll(args)
    }

    /**
     * Run the agent at a fixed interval (in seconds).
     * Minimum recommended: 900 (15 minutes).
     */
    fun startInterval(seconds: Int) {
        startInterval = seconds
    }

    /** Run the agent immediately when loaded by launchd. */
    fun runAtLoad(enabled: Boolean = true) {
        runAtLoad = enabled
    }

    /** Restart the agent automatically if it exits. */
    fun keepAlive(enabled: Boolean = true) {
        keepAlive = enabled
    }

    /** Set the process type (`Background`, `Standard`, `Adaptive`). Defaults to `Background`. */
    fun processType(type: String) {
        processType = type
    }

    /**
     * Adds a calendar-based schedule (maps to `StartCalendarInterval`).
     * Multiple calls create an array of intervals.
     *
     * ```kotlin
     * calendar { hour = 9; minute = 30 }                // daily at 09:30
     * calendar { weekday = 1; hour = 8; minute = 0 }     // every Monday at 08:00
     * ```
     */
    fun calendar(fn: Action<CalendarInterval>) {
        val interval = CalendarInterval()
        fn.execute(interval)
        calendarIntervals.add(interval)
    }

    /** The plist filename: `{label}.plist`. */
    val plistFileName: String get() = "$label.plist"

    companion object {
        private const val serialVersionUID = 1L
    }
}

/**
 * A calendar interval for launchd's `StartCalendarInterval`.
 *
 * All fields are optional — omitted fields act as wildcards.
 * Weekday uses launchd convention: 0 = Sunday, 1 = Monday, ..., 6 = Saturday.
 */
class CalendarInterval : Serializable {
    /** Month of the year (1–12). */
    var month: Int? = null

    /** Day of the month (1–31). */
    var day: Int? = null

    /** Day of the week (0 = Sunday, 1 = Monday, ..., 6 = Saturday). */
    var weekday: Int? = null

    /** Hour of the day (0–23). */
    var hour: Int? = null

    /** Minute of the hour (0–59). */
    var minute: Int? = null

    companion object {
        private const val serialVersionUID = 1L
    }
}
