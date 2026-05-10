package dev.nucleusframework.nucleus.autolaunch.linux

import dev.nucleusframework.nucleus.autolaunch.AutoLaunchBackend
import dev.nucleusframework.nucleus.autolaunch.AutoLaunchConfig
import dev.nucleusframework.nucleus.autolaunch.AutoLaunchResult
import dev.nucleusframework.nucleus.autolaunch.AutoLaunchState
import dev.nucleusframework.nucleus.autolaunch.containsAutostartMarker
import dev.nucleusframework.nucleus.core.runtime.NucleusApp
import java.io.IOException
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.writeText

/**
 * XDG Desktop Portal Background backend (Flatpak sandbox only).
 *
 * The sandbox cannot write to the host's `~/.config/systemd/user/` nor talk to
 * the host's systemd over D-Bus — `org.freedesktop.portal.Background` is the
 * only mechanism available.
 *
 * Detection of login-launch relies on the CLI marker injected into the portal
 * `commandline`. In Flatpak this is safe: the commandline always resolves to
 * `flatpak run <app-id> [--marker]`, which contains no spaces, so the portal's
 * Exec= quoting bug (see xdg-desktop-portal src/background.c `g_strjoinv` call)
 * cannot strike.
 *
 * State is tracked via a local marker file because the portal has no getter.
 */
@Suppress("TooManyFunctions")
internal object FlatpakPortalBackend : AutoLaunchBackend {
    override fun state(): AutoLaunchState {
        if (!NativeAutoLaunchLinuxBridge.isPortalAvailable()) return AutoLaunchState.UNSUPPORTED
        return if (markerFile().exists()) AutoLaunchState.ENABLED else AutoLaunchState.DISABLED
    }

    override fun enable(): AutoLaunchResult {
        if (!NativeAutoLaunchLinuxBridge.isPortalAvailable()) return AutoLaunchResult.UNSUPPORTED
        if (state() == AutoLaunchState.ENABLED) return AutoLaunchResult.UNCHANGED

        val reason = AutoLaunchConfig.backgroundReason ?: defaultReason()
        val rc =
            NativeAutoLaunchLinuxBridge.requestBackground(
                enable = true,
                commandline = buildCommandline(),
                reason = reason,
            )
        return when (rc) {
            NativeAutoLaunchLinuxBridge.RC_OK -> {
                writeMarker()
                AutoLaunchResult.OK
            }
            NativeAutoLaunchLinuxBridge.RC_USER_DENIED -> AutoLaunchResult.BLOCKED_BY_USER
            NativeAutoLaunchLinuxBridge.RC_NO_PORTAL -> AutoLaunchResult.UNSUPPORTED
            else -> AutoLaunchResult.ERROR
        }
    }

    override fun disable(): AutoLaunchResult {
        if (!NativeAutoLaunchLinuxBridge.isPortalAvailable()) return AutoLaunchResult.UNSUPPORTED
        if (state() == AutoLaunchState.DISABLED) return AutoLaunchResult.UNCHANGED

        val reason = AutoLaunchConfig.backgroundReason ?: defaultReason()
        val rc =
            NativeAutoLaunchLinuxBridge.requestBackground(
                enable = false,
                commandline = buildCommandline(),
                reason = reason,
            )
        return when (rc) {
            NativeAutoLaunchLinuxBridge.RC_OK -> {
                deleteMarker()
                AutoLaunchResult.OK
            }
            NativeAutoLaunchLinuxBridge.RC_NO_PORTAL -> AutoLaunchResult.UNSUPPORTED
            else -> AutoLaunchResult.ERROR
        }
    }

    override fun openSystemSettings(): Boolean {
        val candidates =
            listOf(
                arrayOf("gnome-control-center", "applications"),
                arrayOf("xdg-open", System.getProperty("user.home") + "/.config/autostart"),
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

    override fun wasStartedAtLogin(args: Array<String>): Boolean = containsAutostartMarker(args)

    override fun diagnosticSummary(): String =
        "linuxBackend: flatpak-portal\nportalAvailable: ${NativeAutoLaunchLinuxBridge.isPortalAvailable()}\nmarkerFile: ${markerFile()}\n"

    // ---- helpers ------------------------------------------------------

    /**
     * From inside a Flatpak sandbox, `org.freedesktop.portal.Background.RequestBackground`
     * rewrites the `commandline` arg as `flatpak run --command=<cmd[0]> <app-id> <cmd[1..n]>`.
     * So we must pass only the *inner* command (the manifest `command` / binary basename)
     * plus our marker, NOT the outer `flatpak run <id>` wrapper — the portal adds that itself.
     *
     * See xdg-desktop-portal/src/background.c `autostart_command()`.
     */
    private fun buildCommandline(): Array<String> {
        val marker = AutoLaunchConfig.autostartArgument?.takeIf { it.isNotBlank() }
        val innerCommand = resolveInnerCommand() ?: return emptyArray()
        return buildList {
            add(innerCommand)
            if (marker != null) add(marker)
        }.toTypedArray()
    }

    @Suppress("TooGenericExceptionCaught")
    private fun resolveInnerCommand(): String? =
        AutoLaunchConfig.executablePath?.takeIf { it.isNotBlank() }?.substringAfterLast('/')
            ?: try {
                ProcessHandle
                    .current()
                    .info()
                    .command()
                    .orElse(null)
                    ?.substringAfterLast('/')
            } catch (_: Exception) {
                null
            }

    private fun defaultReason(): String {
        val name = NucleusApp.appName ?: NucleusApp.appId
        return "Launch $name at login"
    }

    private fun configRoot(): Path {
        val xdg = System.getenv("XDG_CONFIG_HOME")?.takeIf { it.isNotBlank() }
        return if (xdg != null) Path.of(xdg) else Path.of(System.getProperty("user.home"), ".config")
    }

    private fun markerFile(): Path =
        configRoot().resolve("nucleus").resolve(NucleusApp.appId).resolve("autolaunch.enabled")

    private fun writeMarker() {
        try {
            val f = markerFile()
            f.parent.createDirectories()
            f.writeText("1")
        } catch (_: IOException) {
            // Non-fatal: the portal has persisted autostart regardless. state() may
            // report DISABLED until the next successful enable() resynchronizes.
        }
    }

    private fun deleteMarker() {
        try {
            markerFile().deleteIfExists()
        } catch (_: IOException) {
            // ignore
        }
    }
}
