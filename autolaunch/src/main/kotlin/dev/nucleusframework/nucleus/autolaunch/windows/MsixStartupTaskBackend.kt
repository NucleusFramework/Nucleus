package dev.nucleusframework.nucleus.autolaunch.windows

import dev.nucleusframework.nucleus.autolaunch.AutoLaunchBackend
import dev.nucleusframework.nucleus.autolaunch.AutoLaunchConfig
import dev.nucleusframework.nucleus.autolaunch.AutoLaunchResult
import dev.nucleusframework.nucleus.autolaunch.AutoLaunchState
import dev.nucleusframework.nucleus.core.runtime.NucleusApp

/**
 * MSIX / packaged desktop backend. Requires `<uap5:StartupTask TaskId="...">`
 * declared in the app's manifest.
 *
 * Mapping from native codes (mirrors WinRT StartupTaskState):
 *  - 0 Disabled         → DISABLED
 *  - 1 DisabledByUser   → DISABLED_BY_USER (never call RequestEnableAsync)
 *  - 2 Enabled          → ENABLED
 *  - 3 DisabledByPolicy → DISABLED_BY_POLICY
 *  - 4 EnabledByPolicy  → ENABLED_BY_POLICY
 */
internal object MsixStartupTaskBackend : AutoLaunchBackend {
    private const val DISABLED = 0
    private const val DISABLED_BY_USER = 1
    private const val ENABLED = 2
    private const val DISABLED_BY_POLICY = 3
    private const val ENABLED_BY_POLICY = 4

    override fun state(): AutoLaunchState = mapState(NativeAutoLaunchBridge.msixGetState(taskId()))

    override fun enable(): AutoLaunchResult {
        val current = NativeAutoLaunchBridge.msixGetState(taskId())
        when (current) {
            ENABLED, ENABLED_BY_POLICY -> return AutoLaunchResult.UNCHANGED
            DISABLED_BY_USER -> return AutoLaunchResult.BLOCKED_BY_USER
            DISABLED_BY_POLICY -> return AutoLaunchResult.BLOCKED_BY_POLICY
            -1 -> return AutoLaunchResult.ERROR
        }
        val newState = NativeAutoLaunchBridge.msixRequestEnable(taskId())
        return when (newState) {
            ENABLED, ENABLED_BY_POLICY -> AutoLaunchResult.OK
            DISABLED_BY_USER -> AutoLaunchResult.BLOCKED_BY_USER
            DISABLED_BY_POLICY -> AutoLaunchResult.BLOCKED_BY_POLICY
            else -> AutoLaunchResult.ERROR
        }
    }

    override fun disable(): AutoLaunchResult {
        val current = NativeAutoLaunchBridge.msixGetState(taskId())
        when (current) {
            DISABLED, DISABLED_BY_USER -> return AutoLaunchResult.UNCHANGED
            DISABLED_BY_POLICY -> return AutoLaunchResult.BLOCKED_BY_POLICY
            ENABLED_BY_POLICY -> return AutoLaunchResult.BLOCKED_BY_POLICY
            -1 -> return AutoLaunchResult.ERROR
        }
        val rc = NativeAutoLaunchBridge.msixDisable(taskId())
        return if (rc == 0) AutoLaunchResult.OK else AutoLaunchResult.ERROR
    }

    /**
     * MSIX auto-launches are issued by `sihost.exe` via the Shell Infrastructure Host;
     * the helper DLL walks the process tree (skipping self-spawned jpackage launcher
     * chains) to detect this. Manual launches come from `explorer.exe` / `runtimebroker.exe`.
     */
    override fun wasStartedAtLogin(args: Array<String>): Boolean =
        NativeAutoLaunchBridge.isLaunchedByTaskScheduler() == 1

    private fun taskId(): String =
        AutoLaunchConfig.taskId
            ?: NucleusApp.startupTaskId
            ?: NucleusApp.appId

    private fun mapState(code: Int): AutoLaunchState =
        when (code) {
            DISABLED -> AutoLaunchState.DISABLED
            DISABLED_BY_USER -> AutoLaunchState.DISABLED_BY_USER
            ENABLED -> AutoLaunchState.ENABLED
            DISABLED_BY_POLICY -> AutoLaunchState.DISABLED_BY_POLICY
            ENABLED_BY_POLICY -> AutoLaunchState.ENABLED_BY_POLICY
            else -> AutoLaunchState.UNSUPPORTED
        }
}
