package dev.nucleusframework.lab.probes.lifecycle.autolaunch

import dev.nucleusframework.autolaunch.AutoLaunch
import dev.nucleusframework.autolaunch.AutoLaunchConfig
import dev.nucleusframework.autolaunch.AutoLaunchResult
import dev.nucleusframework.autolaunch.AutoLaunchState
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject

/** Port over `autolaunch`. Every call may block on a native backend: run it off the UI thread. */
interface AutoLaunchGateway {
    val autostartArgument: String?

    fun state(): AutoLaunchState

    /** `backend: …` line of [AutoLaunch.diagnostic] plus the full text. */
    fun diagnostic(): String

    fun startedAtLogin(): Boolean

    fun enable(): AutoLaunchResult

    fun disable(): AutoLaunchResult

    fun openSystemSettings(): Boolean
}

@ContributesBinding(AppScope::class)
@Inject
class NucleusAutoLaunchGateway : AutoLaunchGateway {
    override val autostartArgument: String? get() = AutoLaunchConfig.autostartArgument

    override fun state(): AutoLaunchState = AutoLaunch.state()

    override fun diagnostic(): String = AutoLaunch.diagnostic()

    /**
     * `main`'s args are not reachable from here; the process's own argument list carries the
     * autostart marker just the same (it is a program argument of the launch entry).
     */
    override fun startedAtLogin(): Boolean {
        val args =
            ProcessHandle
                .current()
                .info()
                .arguments()
                .orElse(emptyArray())
        return AutoLaunch.wasStartedAtLogin(args)
    }

    override fun enable(): AutoLaunchResult = AutoLaunch.enable()

    override fun disable(): AutoLaunchResult = AutoLaunch.disable()

    override fun openSystemSettings(): Boolean = AutoLaunch.openSystemSettings()
}
