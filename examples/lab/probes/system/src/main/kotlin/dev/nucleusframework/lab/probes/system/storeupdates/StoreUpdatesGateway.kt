package dev.nucleusframework.lab.probes.system.storeupdates

import dev.nucleusframework.lab.core.Availability
import dev.nucleusframework.store.microsoft.MicrosoftStore
import dev.nucleusframework.store.microsoft.StoreUpdateCheck
import dev.nucleusframework.store.microsoft.StoreUpdateProgress
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.flow.Flow

/** Port over `microsoft-store`'s [StoreUpdates]. Store failures surface as `StoreException`. */
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
    // A broken nucleus_msstore throws a LinkageError from here: it must make the probe report it,
    // not take the Lab down.
    override fun supported(): Availability = Availability.catching { MicrosoftStore.isSupported }

    override suspend fun check(): StoreUpdateCheck = MicrosoftStore.updates.check()

    override fun install(
        silent: Boolean,
        hwnd: Long,
    ): Flow<StoreUpdateProgress> =
        MicrosoftStore.updates.install {
            this.silent = silent
            windows { ownerWindowHandle = hwnd.takeIf { it != 0L } }
        }

    override suspend fun openStorePage() {
        MicrosoftStore.updates.openStorePage()
    }
}
