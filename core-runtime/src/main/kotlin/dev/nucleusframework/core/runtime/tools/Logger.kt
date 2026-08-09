package dev.nucleusframework.core.runtime.tools

import dev.nucleusframework.core.runtime.tools.NucleusLoggingLevel.Companion.DEBUG
import dev.nucleusframework.core.runtime.tools.NucleusLoggingLevel.Companion.ERROR
import dev.nucleusframework.core.runtime.tools.NucleusLoggingLevel.Companion.INFO
import dev.nucleusframework.core.runtime.tools.NucleusLoggingLevel.Companion.VERBOSE
import dev.nucleusframework.core.runtime.tools.NucleusLoggingLevel.Companion.WARN
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.logging.ConsoleHandler
import java.util.logging.Formatter
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger

/**
 * Opts the `dev.nucleusframework` logger namespace into verbose console output.
 *
 * Every Nucleus runtime module logs through `java.util.logging`, so an application that already
 * configures JUL (a `logging.properties` file, a `jul-to-slf4j` bridge, …) should leave this `false`
 * and set the level of the `dev.nucleusframework` logger instead — Nucleus then never touches the
 * JUL configuration.
 *
 * Setting it to `true` raises the `dev.nucleusframework` logger to [nucleusLoggingLevel] and, unless
 * that logger already owns a handler, attaches a colored [ConsoleHandler]. Setting it back to `false`
 * detaches that handler and restores level inheritance.
 *
 * Warnings and errors are published through JUL regardless of this flag.
 */
public var allowNucleusRuntimeLogging: Boolean = false
    set(value) {
        field = value
        applyNucleusLoggingConfiguration()
    }

/** Lowest [NucleusLoggingLevel] published while [allowNucleusRuntimeLogging] is enabled. */
public var nucleusLoggingLevel: NucleusLoggingLevel = VERBOSE
    set(value) {
        field = value
        applyNucleusLoggingConfiguration()
    }

@Deprecated("Renamed to nucleusLoggingLevel", ReplaceWith("nucleusLoggingLevel"))
public var composeNativeTrayLoggingLevel: NucleusLoggingLevel by ::nucleusLoggingLevel

@Deprecated("Renamed to nucleusLoggingLevel", ReplaceWith("nucleusLoggingLevel"))
public var composeNativeTrayloggingLevel: NucleusLoggingLevel by ::nucleusLoggingLevel

/** Verbosity threshold for the Nucleus runtime logs, mapped onto `java.util.logging` levels. */
public class NucleusLoggingLevel private constructor(
    private val label: String,
    private val priority: Int,
    internal val julLevel: Level,
) : Comparable<NucleusLoggingLevel> {
    override fun compareTo(other: NucleusLoggingLevel): Int = priority.compareTo(other.priority)

    override fun toString(): String = label

    /** The available verbosity thresholds, from most to least verbose. */
    public companion object {
        /** Everything, including high-frequency tracing (`java.util.logging` `FINER`). */
        @JvmField
        public val VERBOSE: NucleusLoggingLevel = NucleusLoggingLevel("VERBOSE", 0, Level.FINER)

        /** Diagnostics useful while developing (`java.util.logging` `FINE`). */
        @JvmField
        public val DEBUG: NucleusLoggingLevel = NucleusLoggingLevel("DEBUG", 1, Level.FINE)

        /** Lifecycle milestones (`java.util.logging` `INFO`). */
        @JvmField
        public val INFO: NucleusLoggingLevel = NucleusLoggingLevel("INFO", 2, Level.INFO)

        /** Recoverable problems (`java.util.logging` `WARNING`). */
        @JvmField
        public val WARN: NucleusLoggingLevel = NucleusLoggingLevel("WARN", 3, Level.WARNING)

        /** Failures (`java.util.logging` `SEVERE`). */
        @JvmField
        public val ERROR: NucleusLoggingLevel = NucleusLoggingLevel("ERROR", 4, Level.SEVERE)
    }
}

@Deprecated(
    "Leaked upstream naming, renamed to NucleusLoggingLevel",
    ReplaceWith("NucleusLoggingLevel"),
)
public typealias ComposeNativeTrayLoggingLevel = NucleusLoggingLevel

/**
 * Root logger of the Nucleus namespace. Held strongly on purpose: `LogManager` only keeps weak
 * references to named loggers, so a collected logger would silently drop the level set here.
 */
private val nucleusRootLogger: Logger = Logger.getLogger("dev.nucleusframework")

private val loggingLock = Any()

private var nucleusConsoleHandler: ConsoleHandler? = null

private fun applyNucleusLoggingConfiguration() {
    synchronized(loggingLock) {
        if (!allowNucleusRuntimeLogging) {
            nucleusConsoleHandler?.let {
                nucleusRootLogger.removeHandler(it)
                nucleusRootLogger.useParentHandlers = true
                it.close()
            }
            nucleusConsoleHandler = null
            nucleusRootLogger.level = null
            return
        }

        val level = nucleusLoggingLevel.julLevel
        nucleusRootLogger.level = level

        // Never fight an application that wired its own handler onto the namespace.
        val handler = nucleusConsoleHandler
        if (handler == null && nucleusRootLogger.handlers.isNotEmpty()) return

        val console =
            handler ?: ConsoleHandler().also {
                it.formatter = NucleusLogFormatter()
                nucleusRootLogger.addHandler(it)
                nucleusRootLogger.useParentHandlers = false
                nucleusConsoleHandler = it
            }
        console.level = level
    }
}

private const val COLOR_RED = "\u001b[31m"
private const val COLOR_AQUA = "\u001b[36m"
private const val COLOR_LIGHT_GRAY = "\u001b[37m"
private const val COLOR_ORANGE = "\u001b[38;2;255;165;0m"
private const val COLOR_RESET = "\u001b[0m"

private val timeFormatter =
    DateTimeFormatter
        .ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
        .withZone(ZoneId.systemDefault())

/** Single-line, optionally colored formatter used by the opt-in Nucleus console handler. */
private class NucleusLogFormatter : Formatter() {
    private val colored = System.getenv("NO_COLOR").isNullOrEmpty()

    override fun format(record: LogRecord): String {
        val line = "[${timeFormatter.format(record.instant)}] ${formatMessage(record)}"
        val thrown = record.thrown?.let { "\n${it.stackTraceToString()}" }.orEmpty()
        return if (colored) "${colorOf(record.level)}$line$COLOR_RESET$thrown\n" else "$line$thrown\n"
    }

    private fun colorOf(level: Level): String =
        when {
            level.intValue() >= Level.SEVERE.intValue() -> COLOR_RED
            level.intValue() >= Level.WARNING.intValue() -> COLOR_ORANGE
            level.intValue() >= Level.INFO.intValue() -> COLOR_AQUA
            else -> COLOR_LIGHT_GRAY
        }
}

private val coreLogger: Logger = Logger.getLogger("dev.nucleusframework.core.runtime")

private fun log(
    level: NucleusLoggingLevel,
    message: () -> String,
) {
    if (coreLogger.isLoggable(level.julLevel)) coreLogger.log(level.julLevel, message())
}

internal fun verboseln(message: () -> String): Unit = log(VERBOSE, message)

internal fun debugln(message: () -> String): Unit = log(DEBUG, message)

internal fun infoln(message: () -> String): Unit = log(INFO, message)

internal fun warnln(message: () -> String): Unit = log(WARN, message)

internal fun errorln(message: () -> String): Unit = log(ERROR, message)
