package dev.nucleusframework.tray.utils

import java.util.logging.Level
import java.util.logging.Logger

// Every tray log goes through java.util.logging under the `dev.nucleusframework` namespace, so
// `allowNucleusRuntimeLogging` / `nucleusLoggingLevel` (core-runtime) or the app's own JUL
// configuration control it, like every other Nucleus module.
private val trayLogger: Logger = Logger.getLogger("dev.nucleusframework.tray")

private inline fun log(
    level: Level,
    message: () -> String,
) {
    if (trayLogger.isLoggable(level)) trayLogger.log(level, message())
}

internal fun verboseln(message: () -> String): Unit = log(Level.FINER, message)

internal fun debugln(message: () -> String): Unit = log(Level.FINE, message)

internal fun infoln(message: () -> String): Unit = log(Level.INFO, message)

internal fun warnln(message: () -> String): Unit = log(Level.WARNING, message)

internal fun errorln(message: () -> String): Unit = log(Level.SEVERE, message)
