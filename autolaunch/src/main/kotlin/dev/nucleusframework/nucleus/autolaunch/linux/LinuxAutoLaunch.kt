package dev.nucleusframework.nucleus.autolaunch.linux

import dev.nucleusframework.nucleus.autolaunch.AutoLaunchBackend
import dev.nucleusframework.nucleus.autolaunch.AutoLaunchResult
import dev.nucleusframework.nucleus.autolaunch.AutoLaunchState
import dev.nucleusframework.nucleus.core.runtime.ExecutableRuntime

/**
 * Linux dispatcher. Chooses between systemd user service (host) and
 * XDG Desktop Portal Background (Flatpak sandbox) at first access.
 *
 * **Host (deb/rpm/AppImage/dev)** — [SystemdUserBackend]
 *  - manages `~/.config/systemd/user/<app>.service` via `org.freedesktop.systemd1`
 *  - detects login launch via the `INVOCATION_ID` env var that systemd injects
 *
 * **Flatpak** — [FlatpakPortalBackend]
 *  - the sandbox cannot reach the host's systemd; portal is the only option
 *  - detects login launch via the CLI marker
 *    ([dev.nucleusframework.nucleus.autolaunch.AutoLaunchConfig.autostartArgument])
 *    injected into the portal's `commandline` (safe: `flatpak run <id>` has no spaces,
 *    so the portal's quoting bug on `Exec=` does not strike)
 */
internal object LinuxAutoLaunch : AutoLaunchBackend {
    private val delegate: AutoLaunchBackend by lazy { resolveDelegate() }

    override fun state(): AutoLaunchState = delegate.state()

    override fun enable(): AutoLaunchResult = delegate.enable()

    override fun disable(): AutoLaunchResult = delegate.disable()

    override fun openSystemSettings(): Boolean = delegate.openSystemSettings()

    override fun wasStartedAtLogin(args: Array<String>): Boolean = delegate.wasStartedAtLogin(args)

    override fun diagnosticSummary(): String =
        delegate.diagnosticSummary() + NativeAutoLaunchLinuxBridge.getDiagnostic()

    private fun resolveDelegate(): AutoLaunchBackend {
        if (!NativeAutoLaunchLinuxBridge.isLoaded) return UnsupportedLinuxBackend
        return if (ExecutableRuntime.isFlatpak()) FlatpakPortalBackend else SystemdUserBackend
    }
}

/** Emitted when the JNI library cannot be loaded (unsupported architecture, missing GLib, etc.). */
internal object UnsupportedLinuxBackend : AutoLaunchBackend {
    override fun state(): AutoLaunchState = AutoLaunchState.UNSUPPORTED

    override fun enable(): AutoLaunchResult = AutoLaunchResult.UNSUPPORTED

    override fun disable(): AutoLaunchResult = AutoLaunchResult.UNSUPPORTED

    override fun wasStartedAtLogin(args: Array<String>): Boolean = false

    override fun diagnosticSummary(): String = "native library not loaded\n"
}
