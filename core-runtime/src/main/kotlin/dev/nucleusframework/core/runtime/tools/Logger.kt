package dev.nucleusframework.core.runtime.tools

import dev.nucleusframework.core.runtime.tools.ComposeNativeTrayLoggingLevel.Companion.DEBUG
import dev.nucleusframework.core.runtime.tools.ComposeNativeTrayLoggingLevel.Companion.ERROR
import dev.nucleusframework.core.runtime.tools.ComposeNativeTrayLoggingLevel.Companion.INFO
import dev.nucleusframework.core.runtime.tools.ComposeNativeTrayLoggingLevel.Companion.VERBOSE
import dev.nucleusframework.core.runtime.tools.ComposeNativeTrayLoggingLevel.Companion.WARN
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

public var allowNucleusRuntimeLogging: Boolean = false

@Deprecated("migrate to composeNativeTrayLoggingLevel", ReplaceWith("composeNativeTrayLoggingLevel"))
public var composeNativeTrayloggingLevel: ComposeNativeTrayLoggingLevel by ::composeNativeTrayLoggingLevel

public var composeNativeTrayLoggingLevel: ComposeNativeTrayLoggingLevel = VERBOSE

public class ComposeNativeTrayLoggingLevel private constructor(
    private val priority: Int,
) : Comparable<ComposeNativeTrayLoggingLevel> {
    override fun compareTo(other: ComposeNativeTrayLoggingLevel): Int = priority.compareTo(other.priority)

    public companion object {
        @JvmField
        public val VERBOSE: ComposeNativeTrayLoggingLevel = ComposeNativeTrayLoggingLevel(0)

        @JvmField
        public val DEBUG: ComposeNativeTrayLoggingLevel = ComposeNativeTrayLoggingLevel(1)

        @JvmField
        public val INFO: ComposeNativeTrayLoggingLevel = ComposeNativeTrayLoggingLevel(2)

        @JvmField
        public val WARN: ComposeNativeTrayLoggingLevel = ComposeNativeTrayLoggingLevel(3)

        @JvmField
        public val ERROR: ComposeNativeTrayLoggingLevel = ComposeNativeTrayLoggingLevel(4)
    }
}

private const val COLOR_RED = "\u001b[31m"
private const val COLOR_AQUA = "\u001b[36m"
private const val COLOR_LIGHT_GRAY = "\u001b[37m"
private const val COLOR_ORANGE = "\u001b[38;2;255;165;0m"
private const val COLOR_RESET = "\u001b[0m"

// Time formatter
private val timeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")

private fun getCurrentTimestamp(): String = LocalDateTime.now().format(timeFormatter)

internal fun debugln(message: () -> String) {
    if (allowNucleusRuntimeLogging && composeNativeTrayLoggingLevel <= DEBUG) {
        println("[${getCurrentTimestamp()}] ${message()}")
    }
}

internal fun verboseln(message: () -> String) {
    if (allowNucleusRuntimeLogging && composeNativeTrayLoggingLevel <= VERBOSE) {
        println("[${getCurrentTimestamp()}] ${message()}", COLOR_LIGHT_GRAY)
    }
}

internal fun infoln(message: () -> String) {
    if (allowNucleusRuntimeLogging && composeNativeTrayLoggingLevel <= INFO) {
        println("[${getCurrentTimestamp()}] ${message()}", COLOR_AQUA)
    }
}

internal fun warnln(message: () -> String) {
    if (allowNucleusRuntimeLogging && composeNativeTrayLoggingLevel <= WARN) {
        println("[${getCurrentTimestamp()}] ${message()}", COLOR_ORANGE)
    }
}

internal fun errorln(message: () -> String) {
    if (allowNucleusRuntimeLogging && composeNativeTrayLoggingLevel <= ERROR) {
        println("[${getCurrentTimestamp()}] ${message()}", COLOR_RED)
    }
}

private fun println(
    message: String,
    color: String,
) {
    println(color + message + COLOR_RESET)
}
