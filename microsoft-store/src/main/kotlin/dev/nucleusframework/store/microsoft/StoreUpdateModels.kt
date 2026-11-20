package dev.nucleusframework.store.microsoft

/** What [MicrosoftStoreUpdates.check] found. */
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

/** Options of [MicrosoftStoreUpdates.install]. */
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
 * Windows options of [MicrosoftStoreUpdates.install].
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

    /** Being installed; Windows closes the app during this step, then relaunches it. */
    Installing,
    Completed,

    /** There was nothing to install. */
    UpToDate,

    /** The user declined the Store's dialog. */
    Cancelled,
}

/**
 * One progress report of [MicrosoftStoreUpdates.install].
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
