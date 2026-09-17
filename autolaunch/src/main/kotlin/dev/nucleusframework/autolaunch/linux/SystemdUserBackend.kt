package dev.nucleusframework.autolaunch.linux

import dev.nucleusframework.autolaunch.AutoLaunchBackend
import dev.nucleusframework.autolaunch.AutoLaunchConfig
import dev.nucleusframework.autolaunch.AutoLaunchResult
import dev.nucleusframework.autolaunch.AutoLaunchState
import dev.nucleusframework.core.runtime.NucleusApp
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/**
 * Systemd user service backend (host Linux: deb/rpm/AppImage/dev).
 *
 * Writes `~/.config/systemd/user/<unitName>.service`, enables it via
 * `org.freedesktop.systemd1.Manager.EnableUnitFiles`, and relies on systemd's
 * own quoting of `ExecStart=` so paths with spaces work correctly.
 *
 * Runtime detection of login launch uses the `INVOCATION_ID` environment
 * variable that systemd injects for every unit invocation — equivalent to
 * Windows MSIX `StartupTask` activation or macOS `keyAELaunchedAsLogInItem`.
 */
internal object SystemdUserBackend : AutoLaunchBackend {
    override fun state(): AutoLaunchState =
        when (NativeAutoLaunchLinuxBridge.getUnitFileState(unitName())) {
            NativeAutoLaunchLinuxBridge.RC_STATE_ENABLED,
            NativeAutoLaunchLinuxBridge.RC_STATE_ENABLED_RUNTIME,
            -> AutoLaunchState.ENABLED
            NativeAutoLaunchLinuxBridge.RC_STATE_DISABLED,
            NativeAutoLaunchLinuxBridge.RC_STATE_NOT_INSTALLED,
            -> AutoLaunchState.DISABLED
            else -> AutoLaunchState.UNSUPPORTED
        }

    override fun enable(): AutoLaunchResult {
        if (state() == AutoLaunchState.ENABLED) return AutoLaunchResult.UNCHANGED

        val exe = resolveExecutablePath() ?: return AutoLaunchResult.ERROR
        val unit = unitName()
        val content = buildUnitContent(exe)

        if (NativeAutoLaunchLinuxBridge.writeUnitFile(unit, content) != NativeAutoLaunchLinuxBridge.RC_OK) {
            return AutoLaunchResult.ERROR
        }
        return if (NativeAutoLaunchLinuxBridge.enableUnit(unit) == NativeAutoLaunchLinuxBridge.RC_OK) {
            AutoLaunchResult.OK
        } else {
            // Roll back the unit file so state() stays consistent.
            NativeAutoLaunchLinuxBridge.deleteUnitFile(unit)
            AutoLaunchResult.ERROR
        }
    }

    override fun disable(): AutoLaunchResult {
        if (state() == AutoLaunchState.DISABLED) {
            // Still clean up a stale unit file if it exists outside systemd's view.
            NativeAutoLaunchLinuxBridge.deleteUnitFile(unitName())
            return AutoLaunchResult.UNCHANGED
        }
        val unit = unitName()
        NativeAutoLaunchLinuxBridge.disableUnit(unit)
        NativeAutoLaunchLinuxBridge.deleteUnitFile(unit)
        return AutoLaunchResult.OK
    }

    override fun openSystemSettings(): Boolean {
        val candidates =
            listOf(
                arrayOf("gnome-control-center", "applications"),
                arrayOf("systemadm", "--user"),
                arrayOf("xdg-open", System.getProperty("user.home") + "/.config/systemd/user"),
            )
        for (cmd in candidates) {
            try {
                ProcessBuilder(*cmd).inheritIO().start()
                return true
            } catch (_: IOException) {
                // try next
            }
        }
        return false
    }

    /**
     * Detects whether we're actually running *as* our systemd user unit.
     *
     * `INVOCATION_ID` alone is unreliable: it's inherited by child processes, so
     * a process started by a terminal (which itself runs under a systemd unit like
     * `gnome-terminal-server.service`) will see it set. We verify by reading
     * `/proc/self/cgroup` — if our process lives in a cgroup whose path ends with
     * our unit name, it was launched by systemd **as** that unit, which only
     * happens at login (via `default.target`) or explicit `systemctl --user start`.
     */
    override fun wasStartedAtLogin(args: Array<String>): Boolean {
        val unit = unitName()
        val cgroup =
            try {
                Files.readString(Path.of("/proc/self/cgroup"))
            } catch (_: Exception) {
                return false
            }
        return cgroup.lineSequence().any { line ->
            // cgroup v2: "0::/user.slice/.../nucleusdemo.service"
            // cgroup v1: "N:name=systemd:/.../nucleusdemo.service"
            line.substringAfterLast(':').endsWith("/$unit") ||
                line.substringAfterLast(':') == "/$unit"
        }
    }

    override fun diagnosticSummary(): String {
        val cgroup =
            try {
                Files.readString(Path.of("/proc/self/cgroup")).trim()
            } catch (_: Exception) {
                "(unreadable)"
            }
        return "linuxBackend: systemd-user\n" +
            "unitName: ${unitName()}\n" +
            "invocationId: ${System.getenv("INVOCATION_ID") ?: "(unset)"}\n" +
            "cgroup: $cgroup\n"
    }

    // ---- helpers ------------------------------------------------------

    private fun unitName(): String {
        val base = sanitize(NucleusApp.appId)
        return "$base.service"
    }

    private fun sanitize(id: String): String =
        id.lowercase().map { if (it.isLetterOrDigit() || it in "-_.") it else '-' }.joinToString("")

    @Suppress("TooGenericExceptionCaught")
    private fun resolveExecutablePath(): String? =
        AutoLaunchConfig.executablePath?.takeIf { it.isNotBlank() }
            ?: try {
                ProcessHandle
                    .current()
                    .info()
                    .command()
                    .orElse(null)
            } catch (_: Exception) {
                null
            }

    internal fun buildUnitContent(execPath: String): String {
        val description = NucleusApp.appName ?: NucleusApp.appId
        // systemd ExecStart accepts a double-quoted string for paths with spaces;
        // internal double quotes are backslash-escaped per systemd.unit(5).
        val quotedExec = "\"" + execPath.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        // graphical-session.target is reached AFTER the DE imports DISPLAY / WAYLAND_DISPLAY
        // / XAUTHORITY into the user systemd environment (gnome-session, plasma-workspace,
        // etc. call `systemctl --user import-environment` early in session setup). Firing
        // before that yields HeadlessException in AWT/Compose.
        //
        // The environment is imported by then, but the *appearance* settings are not: on
        // GNOME, gsd-xsettings publishes Xft.dpi into the X resource database later still.
        // Toolkits that sample the display scale once at startup (skiko's autodpi freezes
        // sun.java2d.uiScale at library load) then read an empty resource database and stay
        // at 1.0 for the life of the process — a HiDPI app auto-started at login renders at
        // half size while a manual launch is always correct.
        //
        // gnome-session-x11-services-ready.target is the synchronization point ordered AFTER
        // org.gnome.SettingsDaemon.XSettings.service (which declares
        // `Before=gnome-session-x11-services-ready.target`); the non-"ready" target is ordered
        // *before* it, so both names are listed and only the ready one actually closes the gap.
        // It is active on Wayland sessions too, since Xwayland clients need XSETTINGS. systemd
        // silently ignores ordering dependencies on units that do not exist, so this is a no-op
        // on non-GNOME desktops and on pure-Wayland sessions that publish no Xft.dpi anyway.
        //
        // Caveat: XSettings.service is Type=dbus, so systemd considers it active once the bus
        // name is acquired, not once RESOURCE_MANAGER has been written to the root window. This
        // narrows the race to sub-second rather than provably closing it.
        return """
            |[Unit]
            |Description=$description autostart
            |After=graphical-session.target
            |After=gnome-session-x11-services.target gnome-session-x11-services-ready.target
            |PartOf=graphical-session.target
            |
            |[Service]
            |Type=exec
            |ExecStart=$quotedExec
            |Restart=no
            |
            |[Install]
            |WantedBy=graphical-session.target
            |
            """.trimMargin()
    }
}
