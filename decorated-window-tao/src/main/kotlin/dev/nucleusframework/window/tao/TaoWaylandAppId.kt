package dev.nucleusframework.window.tao

import dev.nucleusframework.core.runtime.ExecutableRuntime
import dev.nucleusframework.core.runtime.NucleusApp

/**
 * The `xdg_toplevel` app_id the Tao windows send on a Wayland display. Wayland compositors pair a
 * window with its launcher entry, and so show the app's icon in the dock, only when the app_id
 * equals the `.desktop` file's basename.
 *
 * GTK 3 sends the launcher binary's name, which for the JVM packages is the jpackage launcher
 * named after the root `packageName`, while the entry is named after `linux.packageName`.
 * They differ whenever `linux.packageName` is set to something else, so the plugin bakes the
 * entry's name ([NucleusApp.linuxDesktopEntryId]) and the bridge sends that instead.
 */
internal object TaoWaylandAppId {
    /** The app_id to send, or `null` to keep GTK's default (the launcher binary's name). */
    fun resolve(
        desktopEntryId: String?,
        env: (String) -> String?,
        isNativeImage: Boolean,
    ): String? {
        // Flatpak installs the entry under the Flatpak app id.
        env("FLATPAK_ID")?.takeIf { it.isNotBlank() }?.let { return it }
        // Snap installs `<snap>_<app>.desktop` and the compositor pairs snaps through their
        // sandbox, not the app_id.
        if (!env("SNAP_NAME").isNullOrBlank()) return null
        // A native image's binary is named after its entry already (`graalvm.imageName`), which
        // the baked id (the JVM packages' name) does not follow.
        if (isNativeImage) return null
        return desktopEntryId?.takeIf { it.isNotBlank() && '/' !in it }
    }

    /** [resolve] for this process. */
    fun current(): String? =
        resolve(
            desktopEntryId = NucleusApp.linuxDesktopEntryId,
            env = System::getenv,
            isNativeImage = ExecutableRuntime.isGraalVmNativeImage,
        )
}
