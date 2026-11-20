package dev.nucleusframework.application

import dev.nucleusframework.location.Geolocation
import dev.nucleusframework.location.LocationAccess
import dev.nucleusframework.location.LocationAccuracy
import dev.nucleusframework.location.LocationAuthorization
import dev.nucleusframework.location.LocationPortalParent
import dev.nucleusframework.location.requestAuthorization
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * Asks for location access like `Geolocation.requestAuthorization`, with the Linux consent
 * dialog (XDG Location portal) attached to this window: `x11:<xid>` on X11 / XWayland, an
 * `xdg_foreign` export on Wayland, unexported once the request is answered. Windows and macOS
 * prompts belong to the system and are unaffected.
 *
 * Requires `nucleus.location` on the app's classpath; `nucleus-application` never ships it.
 */
public suspend fun NucleusWindow.requestLocationAuthorization(
    access: LocationAccess = LocationAccess.Foreground,
    accuracy: LocationAccuracy = LocationAccuracy.Precise,
): LocationAuthorization {
    // The Wayland export blocks until the compositor answers: off the UI thread. NonCancellable:
    // an export resolved as the caller is cancelled must still reach the request, which closes it.
    val parent =
        withContext(Dispatchers.IO + NonCancellable) { unsafe.taoWindow?.xdgPortalParent() }
            ?: return Geolocation.requestAuthorization(access, accuracy)
    return Geolocation.requestAuthorization(
        LocationPortalParent(parent.portalParent, keepAlive = parent as? AutoCloseable),
        access,
        accuracy,
    )
}
