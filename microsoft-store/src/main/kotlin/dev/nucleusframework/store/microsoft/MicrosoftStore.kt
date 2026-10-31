package dev.nucleusframework.store.microsoft

import dev.nucleusframework.core.runtime.Platform
import dev.nucleusframework.store.microsoft.internal.NativeStoreBackend
import dev.nucleusframework.store.microsoft.internal.NativeStoreBridge
import dev.nucleusframework.store.microsoft.internal.StoreBackend
import dev.nucleusframework.store.microsoft.internal.UnsupportedStoreBackend

/**
 * The Microsoft Store (`Windows.Services.Store`) as seen from the app it installed: what the app
 * sells ([purchases]: add-ons, the app's own license and trial) and how it updates ([updates]).
 *
 * ```kotlin
 * if (MicrosoftStore.purchases.entitlement("pro").isActive) unlockPro()
 * if (MicrosoftStore.updates.check() is StoreUpdateCheck.Available) offerUpdate()
 * ```
 *
 * The Store only serves an app it installed — an MSIX/AppX package with a Partner Center listing.
 * An NSIS/MSI/portable build reports [StoreAvailability.NotPackaged]; macOS and Linux, which have
 * no Microsoft Store, report [StoreAvailability.Unsupported], and every call fails with
 * [StoreErrorCode.Unsupported] there.
 */
public object MicrosoftStore {
    @Volatile
    private var override: StoreBackend? = null

    private val default: StoreBackend by lazy {
        when (Platform.Current) {
            Platform.Windows ->
                if (NativeStoreBridge.isAvailable) {
                    NativeStoreBackend(NativeStoreBridge)
                } else {
                    UnsupportedStoreBackend("The Microsoft Store native bridge failed to load")
                }
            else -> UnsupportedStoreBackend("The Microsoft Store is not available on ${Platform.Current}")
        }
    }

    internal val backend: StoreBackend
        get() = override ?: default

    internal fun setBackendForTesting(backend: StoreBackend?) {
        override = backend
    }

    /** `true` on Windows when the Store bridge loaded. [availability] says more. */
    public val isSupported: Boolean
        get() = backend.isSupported

    /** Whether the Store can serve this process right now. */
    public suspend fun availability(): StoreAvailability = backend.availability()

    /** Add-ons, the app's own license and trial, and the updates the Store sends about them. */
    public val purchases: MicrosoftStorePurchases = MicrosoftStorePurchases()

    /** Newer versions of this app on the Store, installed from inside the app. */
    public val updates: MicrosoftStoreUpdates = MicrosoftStoreUpdates()
}
