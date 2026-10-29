package dev.nucleusframework.iap

import kotlinx.coroutines.flow.Flow

/**
 * Updates of this app from the Microsoft Store: whether the Store has a newer version, and
 * installing it from inside the app.
 *
 * The Store also updates apps on its own, in the background, unless the user turned that off;
 * this is for not waiting for it, and for updates Partner Center marks mandatory.
 *
 * ```kotlin
 * when (val check = StoreUpdates.check()) {
 *     is StoreUpdateCheck.Available ->
 *         StoreUpdates.install { windows { ownerWindowHandle = window.nativeHandle } }
 *             .collect { showProgress(it) }
 *     StoreUpdateCheck.UpToDate -> Unit
 * }
 * ```
 *
 * Like [InAppPurchase], it needs a Microsoft Store install: elsewhere every call fails with
 * [StoreErrorCode.NotPackaged] or [StoreErrorCode.Unsupported].
 */
public object StoreUpdates {
    /** `true` where the Store bridge loaded; [InAppPurchase.availability] says more. */
    public val isSupported: Boolean
        get() = InAppPurchase.backend.isSupported

    /**
     * Whether the Store has a newer version of this app. Silent: shows nothing.
     *
     * @throws StoreException when the Store cannot be queried
     */
    public suspend fun check(): StoreUpdateCheck = InAppPurchase.backend.checkUpdates()

    /**
     * Downloads and installs the pending update, reporting its progress; the flow completes after
     * [StoreUpdateState.Completed], [StoreUpdateState.UpToDate] or [StoreUpdateState.Cancelled].
     * By default the Store shows its dialog over the app's window; [StoreUpdateOptions.silent]
     * installs without any. Windows closes the app to install the new version. Cancelling the
     * collection cancels the download.
     *
     * The flow fails with a [StoreException] when the Store gives up, among them
     * [StoreErrorCode.UpdateBlocked] while it waits for a charger or Wi-Fi.
     */
    public fun install(options: StoreUpdateOptions.() -> Unit = {}): Flow<StoreUpdateProgress> =
        InAppPurchase.backend.installUpdates(StoreUpdateOptions().apply(options))

    /**
     * Opens this app's page in the Store app, where the user can update it themselves.
     *
     * @throws StoreException when the Store has no listing for this app or cannot be opened
     */
    public suspend fun openStorePage() {
        InAppPurchase.backend.openStorePage()
    }
}

/** What [StoreUpdates.check] found. */
public sealed interface StoreUpdateCheck {
    /**
     * A newer version waits on the Store.
     *
     * @property version `major.minor.build.revision` of the new package, when the Store says
     * @property isMandatory Partner Center marked the update mandatory: the app should not run on
     *   without it
     */
    public data class Available(
        val version: String?,
        val isMandatory: Boolean,
    ) : StoreUpdateCheck

    /** This install is the latest version. */
    public data object UpToDate : StoreUpdateCheck
}

/** Options of [StoreUpdates.install]. */
public class StoreUpdateOptions internal constructor() {
    /**
     * Installs without any Store window. Windows may still refuse a silent install (policy,
     * metered connection); the flow then fails instead of asking.
     */
    public var silent: Boolean = false

    internal val windows = WindowsStoreUpdateOptions()

    /** Windows options. */
    public fun windows(block: WindowsStoreUpdateOptions.() -> Unit) {
        windows.block()
    }
}

/**
 * Windows options of [StoreUpdates.install].
 *
 * @property ownerWindowHandle the `HWND` the Store's update dialog is parented on
 *   (`TaoWindow.nativeHandle`). Unset: this app's foreground window
 */
public class WindowsStoreUpdateOptions internal constructor() {
    public var ownerWindowHandle: Long? = null
}

/** Where an update stands. */
public enum class StoreUpdateState {
    /** Queued by the Store. */
    Pending,
    Downloading,

    /** Being installed; Windows closes the app during this step. */
    Installing,
    Completed,

    /** There was nothing to install. */
    UpToDate,

    /** The user declined the Store's dialog. */
    Cancelled,
}

/**
 * One progress report of [StoreUpdates.install].
 *
 * @property progress the whole operation, `0.0` to `1.0`, download and install together
 * @property bytesDownloaded bytes of the new package downloaded so far
 * @property bytesTotal size of the new package; `0` while the Store does not know it
 */
public data class StoreUpdateProgress(
    val state: StoreUpdateState,
    val progress: Double,
    val bytesDownloaded: Long,
    val bytesTotal: Long,
)
