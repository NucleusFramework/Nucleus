package dev.nucleusframework.nucleus.servicemanagement

import dev.nucleusframework.nucleus.servicemanagement.macos.NativeMacServiceManagementBridge
import java.util.logging.Logger

/**
 * Manages app services (login items, launch agents, launch daemons)
 * via the macOS `SMAppService` API (macOS 13.0+).
 *
 * Services must be embedded in the app bundle:
 * - Login items: `Contents/Library/LoginItems/<bundle>.app`
 * - Agents: `Contents/Library/LaunchAgents/<plist>.plist`
 * - Daemons: `Contents/Library/LaunchDaemons/<plist>.plist`
 *
 * ```kotlin
 * val manager = AppServiceManager
 *
 * // Register a launch agent
 * val result = manager.register(AppService.Agent("com.myapp.helper.plist"))
 * if (result.isSuccess) {
 *     println("Agent registered")
 * }
 *
 * // Check status
 * val status = manager.status(AppService.Agent("com.myapp.helper.plist"))
 * if (status == AppServiceStatus.REQUIRES_APPROVAL) {
 *     manager.openSystemSettingsLoginItems()
 * }
 * ```
 */
public object AppServiceManager {
    private val logger = Logger.getLogger(AppServiceManager::class.java.name)

    /**
     * Whether SMAppService is available on this platform (macOS 13.0+, native lib loaded).
     */
    @JvmStatic
    public val isAvailable: Boolean by lazy {
        if (!NativeMacServiceManagementBridge.isLoaded) {
            logger.fine("SMAppService native library not available")
            false
        } else {
            NativeMacServiceManagementBridge.nativeIsAvailable()
        }
    }

    /**
     * Registers a service with the system.
     *
     * On success, the service is enabled and eligible to run.
     * If the user has not yet approved background items for this app,
     * the status will be [AppServiceStatus.REQUIRES_APPROVAL].
     *
     * @return [Result.success] if registration succeeded,
     *         [Result.failure] with the error description otherwise
     */
    @JvmStatic
    public fun register(service: AppService): Result<Unit> {
        if (!isAvailable) {
            return Result.failure(UnsupportedOperationException("SMAppService not available"))
        }
        val error = NativeMacServiceManagementBridge.nativeRegister(service.type, service.identifier)
        return if (error == null) {
            Result.success(Unit)
        } else {
            Result.failure(AppServiceException(error))
        }
    }

    /**
     * Unregisters a service from the system.
     *
     * @param service the service to unregister
     * @param callback called when the operation completes, with an error string or `null` on success
     */
    @JvmStatic
    public fun unregister(
        service: AppService,
        callback: (error: String?) -> Unit = {},
    ) {
        if (!isAvailable) {
            callback("SMAppService not available")
            return
        }
        val callbackId = NativeMacServiceManagementBridge.registerCallback(callback)
        NativeMacServiceManagementBridge.nativeUnregister(service.type, service.identifier, callbackId)
    }

    /**
     * Returns the current registration status of a service.
     */
    @JvmStatic
    public fun status(service: AppService): AppServiceStatus {
        if (!isAvailable) return AppServiceStatus.NOT_REGISTERED
        val rawValue = NativeMacServiceManagementBridge.nativeGetStatus(service.type, service.identifier)
        return AppServiceStatus.fromRawValue(rawValue)
    }

    /**
     * Opens the Login Items pane in System Settings (macOS 13.0+).
     *
     * This is useful when [status] returns [AppServiceStatus.REQUIRES_APPROVAL]
     * so the user can approve the background item.
     *
     * @return `true` if the settings pane was opened successfully
     */
    @JvmStatic
    public fun openSystemSettingsLoginItems(): Boolean {
        if (!isAvailable) return false
        return NativeMacServiceManagementBridge.nativeOpenSystemSettingsLoginItems()
    }
}
