package dev.nucleusframework.flatpak

/** Whether this process can use the Flatpak portal, from [Flatpak.availability]. */
public enum class FlatpakAvailability {
    /** Inside a Flatpak, with a Flatpak portal that has update monitors. */
    Available,

    /** Linux, but not inside a Flatpak sandbox (deb, rpm, AppImage, `gradlew run`, …). */
    NotFlatpak,

    /** Inside a Flatpak, but the Flatpak portal is missing or older than version 2 (flatpak 1.6). */
    PortalMissing,

    /** Not Linux, or the native bridge failed to load. */
    Unsupported,
}

/**
 * Where this install stands, as the portal's update monitor reports it. Commits are the
 * app's OSTree commit checksums.
 *
 * @property runningCommit the commit this process runs
 */
public sealed interface FlatpakUpdateStatus {
    public val runningCommit: String

    /**
     * The remote has a newer commit than the installed one: [FlatpakUpdates.install] downloads
     * and deploys it.
     */
    public data class Available(
        override val runningCommit: String,
        val installedCommit: String,
        val remoteCommit: String,
    ) : FlatpakUpdateStatus

    /**
     * A newer commit is already installed but this process still runs the old one — installed
     * by [FlatpakUpdates.install], by GNOME Software / Discover, or by `flatpak update`.
     * [FlatpakUpdates.restart] runs it.
     */
    public data class Installed(
        override val runningCommit: String,
        val installedCommit: String,
    ) : FlatpakUpdateStatus

    /** This process runs the latest commit of the remote. */
    public data class UpToDate(
        override val runningCommit: String,
    ) : FlatpakUpdateStatus
}

/** Where [FlatpakUpdates.install] stands. */
public enum class FlatpakUpdateState {
    /** Downloading and deploying; the first report may come after the portal's update dialog. */
    Installing,

    /** The new commit is installed. This process still runs the old one: [FlatpakUpdates.restart]. */
    Completed,

    /** There was nothing to install. */
    UpToDate,
}

/**
 * One progress report of [FlatpakUpdates.install].
 *
 * @property progress the whole update, `0.0` to `1.0`, every operation together
 * @property operation the current operation, from `0`; an update may also pull a runtime or an
 *   extension, each its own operation
 * @property operationCount the operations of this update, `0` until the portal planned them
 */
public data class FlatpakUpdateProgress(
    val state: FlatpakUpdateState,
    val progress: Double,
    val operation: Int,
    val operationCount: Int,
)

/**
 * The window a portal dialog is attached to: a
 * [parent window identifier](https://flatpak.github.io/xdg-desktop-portal/docs/window-identifiers.html),
 * `x11:<hex xid>` or `wayland:<xdg-foreign handle>`.
 *
 * With Tao windows: `FlatpakParentWindow(parent.portalParent, keepAlive = parent as? AutoCloseable)`
 * with `parent = window.xdgPortalParent()!!`.
 *
 * @property keepAlive closed once the call that borrowed the window is over, never earlier: a
 *     Wayland handle must stay exported until then. Pass its `xdg_foreign` export here.
 */
public data class FlatpakParentWindow(
    public val portalParent: String,
    public val keepAlive: AutoCloseable? = null,
)
