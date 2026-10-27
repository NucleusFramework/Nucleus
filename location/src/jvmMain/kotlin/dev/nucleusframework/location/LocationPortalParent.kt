package dev.nucleusframework.location

/**
 * The window the Linux consent dialog (XDG Location portal) is attached to: a
 * [parent window identifier](https://flatpak.github.io/xdg-desktop-portal/docs/window-identifiers.html),
 * `x11:<hex xid>` or `wayland:<xdg-foreign handle>`. Ignored on Windows and macOS, whose prompts
 * belong to the system.
 *
 * With Tao windows: `LocationPortalParent(parent.portalParent, keepAlive = parent as? AutoCloseable)`
 * with `parent = window.xdgPortalParent()!!` — or, from `nucleus-application`,
 * `NucleusWindow.requestLocationAuthorization`, which resolves all of this.
 *
 * @property keepAlive closed once the request is answered, never earlier: a Wayland handle must
 *     stay exported until then. Pass its `xdg_foreign` export here.
 */
public data class LocationPortalParent(
    public val portalParent: String,
    public val keepAlive: AutoCloseable? = null,
)

/**
 * [LocationProvider.requestAuthorization] with the Linux consent dialog attached to [parent].
 * Only the dialog this call shows is parented: a request made implicitly by a location call has
 * no window to attach to. [LocationPortalParent.keepAlive] is closed before this returns.
 */
public suspend fun Geolocation.requestAuthorization(
    parent: LocationPortalParent,
    access: LocationAccess = LocationAccess.Foreground,
    accuracy: LocationAccuracy = LocationAccuracy.Precise,
): LocationAuthorization =
    try {
        DesktopLocationBackend.requestAuthorization(access, accuracy, parent.portalParent)
    } finally {
        parent.keepAlive?.close()
    }
