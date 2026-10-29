package dev.nucleusframework.lab.probes.system.storeupdates

import dev.nucleusframework.iap.StoreUpdateCheck
import dev.nucleusframework.iap.StoreUpdateProgress
import dev.nucleusframework.iap.StoreUpdates
import dev.nucleusframework.lab.core.Availability
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.flow.Flow

/** Port over `in-app-purchase`'s [StoreUpdates]. Store failures surface as `StoreException`. */
interface StoreUpdatesGateway {
    fun supported(): Availability

    suspend fun check(): StoreUpdateCheck

    fun install(
        silent: Boolean,
        hwnd: Long,
    ): Flow<StoreUpdateProgress>

    suspend fun openStorePage()
}

@ContributesBinding(AppScope::class)
@Inject
class NucleusStoreUpdatesGateway : StoreUpdatesGateway {
    // A broken nucleus_iap throws a LinkageError from here: it must make the probe report it,
    // not take the Lab down.
    override fun supported(): Availability = Availability.catching { StoreUpdates.isSupported }

    override suspend fun check(): StoreUpdateCheck = StoreUpdates.check()

    override fun install(
        silent: Boolean,
        hwnd: Long,
    ): Flow<StoreUpdateProgress> =
        StoreUpdates.install {
            this.silent = silent
            windows { ownerWindowHandle = hwnd.takeIf { it != 0L } }
        }

    override suspend fun openStorePage() {
        StoreUpdates.openStorePage()
    }
}
