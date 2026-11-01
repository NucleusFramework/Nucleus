package dev.nucleusframework.store.microsoft

/** Marks the [PurchaseOptions] DSL. */
@DslMarker
public annotation class PurchaseDsl

/**
 * Per-platform options of [MicrosoftStorePurchases.purchase]. Each block only applies on its platform
 * and is ignored elsewhere.
 */
@PurchaseDsl
public class PurchaseOptions internal constructor() {
    internal val windows = WindowsPurchaseOptions()

    /** Options applied when the purchase runs on the Microsoft Store. */
    public fun windows(block: WindowsPurchaseOptions.() -> Unit) {
        windows.block()
    }
}

/**
 * Microsoft Store purchase options.
 *
 * @property ownerWindowHandle the `HWND` the Store's purchase dialog is parented on
 *   (`TaoWindow.nativeHandle`). Unset: this app's foreground window, else any visible
 *   top-level window of this process. The Store keeps the first window it was given for the
 *   life of the process.
 * @property skuStoreId buy this SKU (`9NBLGGH69M0B/0010`) of the add-on instead of its default
 */
@PurchaseDsl
public class WindowsPurchaseOptions internal constructor() {
    public var ownerWindowHandle: Long? = null
    public var skuStoreId: String? = null
}
