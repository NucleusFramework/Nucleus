package dev.nucleusframework.tray.app

import java.util.logging.Level
import java.util.logging.Logger

// Same JUL logger as the tray module, so one level controls both.
private val trayAppLogger: Logger = Logger.getLogger("dev.nucleusframework.tray")

internal fun debugln(message: () -> String) {
    if (trayAppLogger.isLoggable(Level.FINE)) trayAppLogger.log(Level.FINE, message())
}
