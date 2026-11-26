package dev.nucleusframework.store.microsoft

import kotlinx.coroutines.flow.Flow

/**
 * Updates of this app from the Microsoft Store: whether the Store has a newer version, and
 * installing it from inside the app. Reached through [MicrosoftStore.updates].
 *
 * The Store also updates apps on its own, in the background, unless the user turned that off;
 * this is for not waiting for it, and for updates Partner Center marks mandatory.
 *
 * ```kotlin
 * val updates = MicrosoftStore.updates
 * when (val check = updates.check()) {
 *     is StoreUpdateCheck.Available ->
 *         updates.install { windows { ownerWindowHandle = window.nativeHandle } }
 *             .collect { showProgress(it) }
 *     StoreUpdateCheck.UpToDate -> Unit
 * }
 * ```
 *
 * It needs a Microsoft Store install: elsewhere every call fails with
 * [StoreErrorCode.NotPackaged] or [StoreErrorCode.Unsupported].
 */
public class MicrosoftStoreUpdates internal constructor() {
    private val backend
        get() = MicrosoftStore.backend

    /**
     * Whether the Store has a newer version of this app. Silent: shows nothing.
     *
     * @throws StoreException when the Store cannot be queried
     */
    public suspend fun check(): StoreUpdateCheck = backend.checkUpdates()

    /**
     * Downloads and installs the pending update, reporting its progress; the flow completes after
     * [StoreUpdateState.Completed], [StoreUpdateState.UpToDate] or [StoreUpdateState.Cancelled].
     * By default the Store shows its dialog over the app's window; [StoreUpdateOptions.silent]
     * installs without any. Cancelling the collection cancels the download.
     *
     * Windows closes the app to install the new version. Before installing, this registers the
     * app for restart (`RegisterApplicationRestart`, for updates only), which is how Microsoft
     * documents getting it relaunched afterwards — and Windows only does so for an app that has
     * run for at least 60 seconds. The relaunch gets no command-line arguments.
     *
     * The flow fails with a [StoreException] when the Store gives up, among them
     * [StoreErrorCode.UpdateBlocked] while it waits for a charger or Wi-Fi.
     */
    public fun install(options: StoreUpdateOptions.() -> Unit = {}): Flow<StoreUpdateProgress> =
        backend.installUpdates(StoreUpdateOptions().apply(options))

    /**
     * Opens this app's page in the Store app, where the user can update it themselves.
     *
     * @throws StoreException when the Store has no listing for this app or cannot be opened
     */
    public suspend fun openStorePage() {
        backend.openStorePage()
    }
}
