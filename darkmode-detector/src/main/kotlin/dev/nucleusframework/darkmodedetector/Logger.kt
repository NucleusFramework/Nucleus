package dev.nucleusframework.darkmodedetector

import java.util.logging.Level
import java.util.logging.Logger

private val logger: Logger = Logger.getLogger("dev.nucleusframework.darkmodedetector")

internal fun debugln(
    tag: String,
    message: () -> String,
) {
    if (logger.isLoggable(Level.FINE)) logger.log(Level.FINE, "[$tag] ${message()}")
}

internal fun errorln(
    tag: String,
    message: () -> String,
) {
    if (logger.isLoggable(Level.SEVERE)) logger.log(Level.SEVERE, "[$tag] ${message()}")
}

internal fun errorln(
    tag: String,
    throwable: Throwable,
    message: () -> String,
) {
    if (logger.isLoggable(Level.SEVERE)) logger.log(Level.SEVERE, "[$tag] ${message()}", throwable)
}
