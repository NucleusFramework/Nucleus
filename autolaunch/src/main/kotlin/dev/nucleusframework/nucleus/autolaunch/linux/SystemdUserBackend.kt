package dev.nucleusframework.nucleus.autolaunch.linux

import dev.nucleusframework.nucleus.autolaunch.AutoLaunchBackend
import dev.nucleusframework.nucleus.autolaunch.AutoLaunchConfig
import dev.nucleusframework.nucleus.autolaunch.AutoLaunchResult
import dev.nucleusframework.nucleus.autolaunch.AutoLaunchState
import dev.nucleusframework.nucleus.core.runtime.NucleusApp
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

    private fun buildUnitContent(execPath: String): String {
        val description = NucleusApp.appName ?: NucleusApp.appId
        // systemd ExecStart accepts a double-quoted string for paths with spaces;
        // internal double quotes are backslash-escaped per systemd.unit(5).
        val quotedExec = "\"" + execPath.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        // graphical-session.target is reached AFTER the DE imports DISPLAY / WAYLAND_DISPLAY
        // / XAUTHORITY into the user systemd environment (gnome-session, plasma-workspace,
        // etc. call `systemctl --user import-environment` early in session setup). Firing
        // before that yields HeadlessException in AWT/Compose.
        return """
            |[Unit]
            |Description=$description autostart
            |After=graphical-session.target
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
