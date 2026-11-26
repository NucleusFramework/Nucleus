package dev.nucleusframework.lab.probes.shell.common

import dev.nucleusframework.core.runtime.tools.LinuxDesktopFileDetector
import dev.nucleusframework.lab.core.Availability
import dev.nucleusframework.launcher.linux.LinuxLauncherEntry

/**
 * The identity shell surfaces are keyed on, shared by every probe that writes to the dock,
 * the taskbar button or a launcher entry: the `.desktop` file on Linux, the loaded launcher
 * library on Windows.
 */
object LauncherIdentity {
    /** The `.desktop` id detected for this process, if any. */
    val linuxDesktopFile: String? by lazy { LinuxDesktopFileDetector.desktopFilename }

    /** Whether the Unity LauncherEntry bridge is loaded at all. */
    fun linuxLauncherLibrary(): Availability =
        Availability.of(LinuxLauncherEntry.isAvailable) { "nucleus_launcher_linux not loaded" }

    /** Whether a launcher entry can be addressed: the bridge is loaded and a `.desktop` file matches. */
    fun linuxLauncherEntry(): Availability =
        when {
            !LinuxLauncherEntry.isAvailable -> linuxLauncherLibrary()
            linuxDesktopFile == null ->
                Availability.Unavailable("no .desktop file matches this process; launcher entries are keyed on it")
            else -> Availability.Available
        }

    /** `application://<desktop file>`, the id a LauncherEntry signal carries. */
    fun linuxAppUri(desktopFile: String): String = LinuxLauncherEntry.appUri(desktopFile)

    /** Availability of a `launcher-windows` API whose native library flag is [loaded]. */
    fun windowsLauncher(loaded: Boolean): Availability =
        Availability.of(loaded) { "nucleus_launcher_windows not loaded" }
}
