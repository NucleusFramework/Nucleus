package dev.nucleusframework.nucleus.autolaunch.macos

import dev.nucleusframework.nucleus.autolaunch.AutoLaunchBackend
import dev.nucleusframework.nucleus.autolaunch.AutoLaunchResult
import dev.nucleusframework.nucleus.autolaunch.AutoLaunchState
import dev.nucleusframework.nucleus.servicemanagement.AppService
import dev.nucleusframework.nucleus.servicemanagement.AppServiceManager
import dev.nucleusframework.nucleus.servicemanagement.AppServiceStatus

/**
 * Unified macOS backend using `SMAppService.mainApp` (macOS 13.0+).
 *
 * The main application registers itself as a login item — no helper app, no
 * bundled plist, no build-time Gradle plugin changes. This is the pattern used
 * by `sindresorhus/LaunchAtLogin-Modern` and is the one that places the entry
 * under **System Settings → General → Login Items → Open at Login** (as
 * opposed to "Allow in the Background" which is what `SMAppService.agent`
 * produces).
 *
 * Works identically for DMG (Developer ID) and PKG (Mac App Store sandboxed)
 * distributions. Runtime auto-launch detection uses the official
 * `kAEOpenApplication` AppleEvent carrying `keyAELaunchedAsLogInItem`, exposed
 * via [AppServiceManager.wasLaunchedAtLogin].
 */
internal object MacSMAppServiceBackend : AutoLaunchBackend {
    private val service: AppService = AppService.MainApp

    override fun state(): AutoLaunchState {
        if (!AppServiceManager.isAvailable) return AutoLaunchState.UNSUPPORTED
        return when (AppServiceManager.status(service)) {
            AppServiceStatus.ENABLED -> AutoLaunchState.ENABLED
            AppServiceStatus.NOT_REGISTERED -> AutoLaunchState.DISABLED
            AppServiceStatus.REQUIRES_APPROVAL -> AutoLaunchState.DISABLED_BY_USER
            // NOT_FOUND on macOS 14+ may mean the BackgroundTaskManagement DB
            // has no record yet — treat as DISABLED so enable() can create it.
            AppServiceStatus.NOT_FOUND -> AutoLaunchState.DISABLED
        }
    }

    override fun enable(): AutoLaunchResult {
        when (state()) {
            AutoLaunchState.ENABLED -> return AutoLaunchResult.UNCHANGED
            AutoLaunchState.DISABLED_BY_USER -> return AutoLaunchResult.BLOCKED_BY_USER
            AutoLaunchState.UNSUPPORTED -> return AutoLaunchResult.UNSUPPORTED
            else -> {}
        }
        val result = AppServiceManager.register(service)
        if (result.isFailure) return AutoLaunchResult.ERROR
        return when (state()) {
            AutoLaunchState.ENABLED -> AutoLaunchResult.OK
            AutoLaunchState.DISABLED_BY_USER -> AutoLaunchResult.BLOCKED_BY_USER
            else -> AutoLaunchResult.ERROR
        }
    }

    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    override fun disable(): AutoLaunchResult {
        when (state()) {
            AutoLaunchState.DISABLED, AutoLaunchState.UNSUPPORTED -> return AutoLaunchResult.UNCHANGED
            else -> {}
        }
        var error: String? = "pending"
        AppServiceManager.unregister(service) { err -> error = err }
        // `unregister` is an asynchronous XPC round-trip; wait briefly for the
        // callback before reporting a state to the caller.
        val deadline = System.nanoTime() + WAIT_NANOS
        while (error == "pending" && System.nanoTime() < deadline) {
            try {
                Thread.sleep(WAIT_POLL_MS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return AutoLaunchResult.ERROR
            }
        }
        return if (error == null) AutoLaunchResult.OK else AutoLaunchResult.ERROR
    }

    override fun openSystemSettings(): Boolean = AppServiceManager.openSystemSettingsLoginItems()

    /**
     * Apple exposes no public API to detect `SMAppService.mainApp` launches
     * (open feedback FB10207829, filed June 2022) and `kAELaunchedAsLogInItem`
     * fires only for legacy `SMLoginItemSetEnabled`.
     *
     * Empirical signal: `launchd` injects `LaunchInstanceID` into the
     * environment of every process it spawns as a managed job — including
     * `SMAppService.mainApp` login items. Launches initiated by the user
     * (Finder, Dock, Spotlight, `open(1)`) go through LaunchServices and do
     * not carry this variable.
     */
    override fun wasStartedAtLogin(args: Array<String>): Boolean = System.getenv(LAUNCHD_INSTANCE_ENV) != null

    override fun diagnosticSummary(): String =
        buildString {
            val available = AppServiceManager.isAvailable
            appendLine("serviceManagement.isAvailable: $available")
            appendLine("service: SMAppService.mainApp")
            if (available) {
                appendLine("rawStatus: ${AppServiceManager.status(service)}")
            }
            appendLine("$LAUNCHD_INSTANCE_ENV present: ${System.getenv(LAUNCHD_INSTANCE_ENV) != null}")
        }

    private const val LAUNCHD_INSTANCE_ENV = "LaunchInstanceID"

    private const val WAIT_NANOS: Long = 2_000_000_000L
    private const val WAIT_POLL_MS: Long = 25L
}
