package dev.nucleusframework.lab.probes.system.flatpakupdates

import dev.nucleusframework.flatpak.Flatpak
import dev.nucleusframework.flatpak.FlatpakAvailability
import dev.nucleusframework.flatpak.FlatpakParentWindow
import dev.nucleusframework.flatpak.FlatpakUpdateProgress
import dev.nucleusframework.flatpak.FlatpakUpdateStatus
import dev.nucleusframework.lab.core.Availability
import dev.nucleusframework.window.tao.TaoWindow
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext

/** Port over `flatpak`'s [Flatpak.updates]. Portal failures surface as `FlatpakException`. */
interface FlatpakUpdatesGateway {
    suspend fun supported(): Availability

    fun appId(): String?

    fun status(): Flow<FlatpakUpdateStatus>

    fun install(window: TaoWindow?): Flow<FlatpakUpdateProgress>

    suspend fun restart(): Nothing

    suspend fun openStorePage(window: TaoWindow?)
}

@ContributesBinding(AppScope::class)
@Inject
class NucleusFlatpakUpdatesGateway : FlatpakUpdatesGateway {
    // A broken nucleus_flatpak throws a LinkageError from here: it must make the probe report it,
    // not take the Lab down.
    override suspend fun supported(): Availability =
        runCatching { Flatpak.availability() }.fold(
            onSuccess = { availability ->
                Availability.of(availability == FlatpakAvailability.Available) {
                    when (availability) {
                        FlatpakAvailability.NotFlatpak -> "not a Flatpak (install it from a Flatpak remote)"
                        FlatpakAvailability.PortalMissing -> "no Flatpak portal with update monitors"
                        else -> "Linux only"
                    }
                }
            },
            onFailure = { Availability.catching { throw it } },
        )

    override fun appId(): String? = Flatpak.appId

    override fun status(): Flow<FlatpakUpdateStatus> = Flatpak.updates.status

    override fun install(window: TaoWindow?): Flow<FlatpakUpdateProgress> =
        flow { emitAll(Flatpak.updates.install(parentOf(window))) }

    override suspend fun restart(): Nothing = Flatpak.updates.restart()

    override suspend fun openStorePage(window: TaoWindow?) {
        Flatpak.updates.openStorePage(parentOf(window))
    }

    // A Wayland export waits on the event loop: never resolved on it.
    private suspend fun parentOf(window: TaoWindow?): FlatpakParentWindow? =
        withContext(Dispatchers.IO + NonCancellable) { window?.xdgPortalParent() }
            ?.let { FlatpakParentWindow(it.portalParent, keepAlive = it as? AutoCloseable) }
}
