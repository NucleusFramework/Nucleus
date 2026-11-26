package dev.nucleusframework.flatpak

import dev.nucleusframework.core.runtime.SingleInstanceManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onCompletion
import kotlin.system.exitProcess

/**
 * Updates of this app from its Flatpak remote: whether a newer commit waits there, installing it
 * from inside the app, and restarting into it. Reached through [Flatpak.updates].
 *
 * The desktop's store (GNOME Software, Discover) also updates Flatpaks in the background; this is
 * for not waiting for it. Its updates still show up here, as [FlatpakUpdateStatus.Installed].
 *
 * ```kotlin
 * val updates = Flatpak.updates
 * updates.status.collect { status ->
 *     when (status) {
 *         is FlatpakUpdateStatus.Available ->
 *             updates.install(parent).collect { showProgress(it) }   // then updates.restart()
 *         is FlatpakUpdateStatus.Installed -> offerRestart()
 *         is FlatpakUpdateStatus.UpToDate -> Unit
 *     }
 * }
 * ```
 *
 * It needs a Flatpak install: elsewhere every call fails with [FlatpakErrorCode.NotFlatpak] or
 * [FlatpakErrorCode.Unsupported].
 */
public class FlatpakUpdates internal constructor() {
    private val backend
        get() = Flatpak.backend

    /**
     * What the portal's update monitor reports about this install. Each collection runs a monitor
     * of its own, closed when the collection ends.
     *
     * The portal has no "check now": it checks every monitor of the session on one timer, every
     * 30 minutes by default (`flatpak-portal --poll-timeout`), and the first status arrives on the
     * first tick after the collection starts — up to 30 minutes later. It reports again only when
     * the installed or the remote commit changes.
     *
     * The flow fails with a [FlatpakException] when the monitor cannot start or the portal goes
     * away.
     */
    public val status: Flow<FlatpakUpdateStatus>
        get() = backend.status()

    /**
     * Downloads and deploys the newer commit, reporting its progress; the flow completes after
     * [FlatpakUpdateState.Completed] or [FlatpakUpdateState.UpToDate]. This process keeps running
     * the old commit: [restart] runs the new one.
     *
     * The first install shows the portal's "Update …?" dialog over [parent] (its `keepAlive` is
     * closed when the flow ends); Flatpak remembers the answer, and a "Deny" fails this and every
     * later install with [FlatpakErrorCode.UpdateDenied]. A new version that asks for more
     * permissions fails with [FlatpakErrorCode.NewPermissionsRequired]: only the store can
     * install it ([openStorePage]).
     *
     * Cancelling the collection closes the update monitor, which interrupts the update.
     */
    public fun install(parent: FlatpakParentWindow? = null): Flow<FlatpakUpdateProgress> =
        backend
            .install(parent?.portalParent.orEmpty())
            .onCompletion { parent?.keepAlive?.close() }

    /**
     * Starts the newest installed commit of this app and exits this process — after
     * [install], or on [FlatpakUpdateStatus.Installed].
     *
     * The new process gets [arguments], by default this process' own; it runs in a sandbox of its
     * own, started by the portal (`Spawn` with `FLATPAK_SPAWN_FLAGS_LATEST_VERSION`). The
     * single-instance lock is handed over to it first ([SingleInstanceManager.releaseForHandoff]),
     * so it does not mistake this process for a running instance; if the portal then refuses to
     * start it, this throws and the process runs on without the lock.
     *
     * @throws FlatpakException when the portal cannot start the new process
     */
    public suspend fun restart(arguments: List<String>? = null): Nothing {
        val info = ProcessHandle.current().info()
        val command =
            info.command().orElse(null)
                ?: throw FlatpakException(FlatpakErrorCode.Internal, "The command of this process is unknown")
        val args = arguments ?: info.arguments().map { it.toList() }.orElse(emptyList())
        SingleInstanceManager.releaseForHandoff()
        backend.spawnLatest(System.getProperty("user.dir") ?: "/", listOf(command) + args)
        exitProcess(0)
    }

    /**
     * Opens this app's page in the desktop's store (`appstream://<app id>`, through the OpenURI
     * portal) — GNOME Software or Discover — where the user can update it themselves, permission
     * changes included. [parent]'s `keepAlive` is closed before this returns.
     *
     * @throws FlatpakException when the app runs outside a Flatpak or the desktop cannot open it
     */
    public suspend fun openStorePage(parent: FlatpakParentWindow? = null) {
        try {
            val appId =
                Flatpak.appId
                    ?: throw FlatpakException(FlatpakErrorCode.NotFlatpak, "FLATPAK_ID is not set: not a Flatpak")
            backend.openUri(parent?.portalParent.orEmpty(), "appstream://$appId")
        } finally {
            parent?.keepAlive?.close()
        }
    }
}
