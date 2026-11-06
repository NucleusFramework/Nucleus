package dev.nucleusframework.lab.probes.lifecycle.smappservice

import dev.nucleusframework.lab.core.mvi.Stamped
import dev.nucleusframework.lab.core.mvi.stamped
import dev.nucleusframework.servicemanagement.AppService
import dev.nucleusframework.servicemanagement.AppServiceManager
import dev.nucleusframework.servicemanagement.AppServiceStatus
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/** The services the probe drives, each with what its status should be in a correct build. */
enum class LabService(
    val label: String,
    val expectation: String,
) {
    MainApp("Main app (login item)", "NOT_REGISTERED until registered, then ENABLED"),
    HeartbeatAgent("Heartbeat launch agent", "packaged: registrable; dev run: NOT_FOUND (no bundle plist)"),
    UndeclaredDaemon("Undeclared daemon", "always NOT_FOUND: no plist ships for it"),
    ;

    val service: AppService
        get() =
            when (this) {
                MainApp -> AppService.MainApp
                HeartbeatAgent -> AppService.Agent(HeartbeatFixture.AGENT_LABEL)
                UndeclaredDaemon -> AppService.Daemon("dev.nucleusframework.lab.undeclared-daemon")
            }
}

/** Port over `service-management-macos`. */
interface AppServiceGateway {
    val isAvailable: Boolean

    fun status(service: LabService): AppServiceStatus

    fun register(service: LabService): Result<Unit>

    /** Completes on the SMAppService completion queue; the stamp says which thread that was. */
    suspend fun unregister(service: LabService): Stamped<String?>

    fun openLoginItems(): Boolean

    fun heartbeats(): List<String>
}

@ContributesBinding(AppScope::class)
@Inject
class NucleusAppServiceGateway : AppServiceGateway {
    override val isAvailable: Boolean get() = AppServiceManager.isAvailable

    override fun status(service: LabService): AppServiceStatus = AppServiceManager.status(service.service)

    override fun register(service: LabService): Result<Unit> = AppServiceManager.register(service.service)

    override suspend fun unregister(service: LabService): Stamped<String?> =
        suspendCancellableCoroutine { continuation ->
            AppServiceManager.unregister(service.service) { error -> continuation.resume(error.stamped()) }
        }

    override fun openLoginItems(): Boolean = AppServiceManager.openSystemSettingsLoginItems()

    override fun heartbeats(): List<String> = HeartbeatFixture.log.tail(HEARTBEATS)

    private companion object {
        const val HEARTBEATS = 10
    }
}
