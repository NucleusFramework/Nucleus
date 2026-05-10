package dev.nucleusframework.nucleus.autolaunch.windows

import dev.nucleusframework.nucleus.autolaunch.AutoLaunchBackend
import dev.nucleusframework.nucleus.autolaunch.AutoLaunchConfig
import dev.nucleusframework.nucleus.autolaunch.AutoLaunchResult
import dev.nucleusframework.nucleus.autolaunch.AutoLaunchState
import dev.nucleusframework.nucleus.core.runtime.NucleusApp

/**
 * Registry-based backend for classic Win32 apps (MSI / NSIS / portable).
 *
 * Reads/writes `HKCU\Software\Microsoft\Windows\CurrentVersion\Run` for the
 * launch entry, and consults `HKCU\Software\Microsoft\Windows\CurrentVersion\Explorer\StartupApproved\Run`
 * to detect the user's Task Manager / Settings toggle.
 *
 * Invariants:
 *  - Never rewrite StartupApproved during silent updates — only in response to
 *    explicit user action via our own UI.
 *  - If state is [AutoLaunchState.DISABLED_BY_USER], [enable] returns
 *    [AutoLaunchResult.BLOCKED_BY_USER] and does not touch the registry.
 */
internal object Win32RegistryBackend : AutoLaunchBackend {
    override fun state(): AutoLaunchState {
        if (!NativeAutoLaunchBridge.isLoaded) return AutoLaunchState.UNSUPPORTED
        return when (NativeAutoLaunchBridge.regReadState(valueName())) {
            0 -> AutoLaunchState.DISABLED
            1 -> AutoLaunchState.ENABLED
            2 -> AutoLaunchState.DISABLED_BY_USER
            else -> AutoLaunchState.UNSUPPORTED
        }
    }

    override fun enable(): AutoLaunchResult {
        if (!NativeAutoLaunchBridge.isLoaded) return AutoLaunchResult.UNSUPPORTED
        when (state()) {
            AutoLaunchState.ENABLED -> return AutoLaunchResult.UNCHANGED
            AutoLaunchState.DISABLED_BY_USER -> return AutoLaunchResult.BLOCKED_BY_USER
            else -> {}
        }
        val command = buildRunCommand() ?: return AutoLaunchResult.ERROR
        val rc = NativeAutoLaunchBridge.regWriteRun(valueName(), command)
        return if (rc == 0) AutoLaunchResult.OK else AutoLaunchResult.ERROR
    }

    override fun disable(): AutoLaunchResult {
        if (!NativeAutoLaunchBridge.isLoaded) return AutoLaunchResult.UNSUPPORTED
        if (state() == AutoLaunchState.DISABLED) return AutoLaunchResult.UNCHANGED
        // Delete both entries: Run to actually stop launching, StartupApproved
        // to avoid a ghost entry in Task Manager.
        val rc = NativeAutoLaunchBridge.regDeleteRun(valueName(), alsoStartupApproved = true)
        return if (rc == 0) AutoLaunchResult.OK else AutoLaunchResult.ERROR
    }

    private fun valueName(): String =
        AutoLaunchConfig.registryValueName
            ?: NucleusApp.appName
            ?: NucleusApp.appId

    private fun buildRunCommand(): String? {
        val exe = AutoLaunchConfig.executablePath ?: resolveExecutablePath() ?: return null
        val quoted = "\"$exe\""
        val arg = AutoLaunchConfig.autostartArgument?.takeIf { it.isNotBlank() }
        return if (arg != null) "$quoted $arg" else quoted
    }

    @Suppress("TooGenericExceptionCaught")
    private fun resolveExecutablePath(): String? =
        try {
            ProcessHandle
                .current()
                .info()
                .command()
                .orElse(null)
        } catch (_: Exception) {
            null
        }
}
