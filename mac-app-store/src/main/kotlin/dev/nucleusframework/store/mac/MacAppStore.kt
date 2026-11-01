package dev.nucleusframework.store.mac

import dev.nucleusframework.core.runtime.Platform
import dev.nucleusframework.store.mac.internal.DevConfiguration
import dev.nucleusframework.store.mac.internal.NativeMacStoreBridge
import dev.nucleusframework.store.mac.internal.NativeStoreBackend
import dev.nucleusframework.store.mac.internal.StoreBackend
import dev.nucleusframework.store.mac.internal.UnsupportedStoreBackend

/**
 * The Mac App Store (StoreKit 2) as seen from the app it sells: what the app sells
 * ([purchases]).
 *
 * ```kotlin
 * val pro = MacAppStore.purchases.product("pro") ?: return
 * showPrice(pro.displayPrice)
 * ```
 *
 * StoreKit identifies the app by its bundle: a packaged `.app` reaches the store, a bare `java`
 * launch reports [StoreAvailability.NotBundled]. Windows and Linux report
 * [StoreAvailability.Unsupported], and every call fails with [StoreErrorCode.Unsupported] there.
 *
 * **Development without App Store Connect**: launch the app bundle with
 * `-Dnucleus.macAppStore.storeKitConfiguration=/path/to/Products.storekit` and StoreKit serves
 * the products of that file (Xcode's StoreKit Testing, in this process, no Apple account). The
 * bundle must be signed with the `com.apple.security.get-task-allow` entitlement (an ad-hoc
 * signature is enough) and known to LaunchServices (opened once, or `lsregister -f`). Xcode must
 * be installed: StoreKitTest ships with it.
 */
public object MacAppStore {
    @Volatile
    private var override: StoreBackend? = null

    private val default: StoreBackend by lazy {
        when (Platform.Current) {
            Platform.MacOS ->
                if (NativeMacStoreBridge.isAvailable) {
                    NativeStoreBackend(NativeMacStoreBridge, DevConfiguration.fromSystem())
                } else {
                    UnsupportedStoreBackend("The Mac App Store native bridge failed to load")
                }
            else -> UnsupportedStoreBackend("The Mac App Store is not available on ${Platform.Current}")
        }
    }

    internal val backend: StoreBackend
        get() = override ?: default

    internal fun setBackendForTesting(backend: StoreBackend?) {
        override = backend
    }

    /** `true` on macOS when the StoreKit bridge loaded. [availability] says more. */
    public val isSupported: Boolean
        get() = backend.isSupported

    /** Whether the store can serve this process right now. */
    public suspend fun availability(): StoreAvailability = backend.availability()

    /** The in-app products of this app. */
    public val purchases: MacAppStorePurchases = MacAppStorePurchases()
}
