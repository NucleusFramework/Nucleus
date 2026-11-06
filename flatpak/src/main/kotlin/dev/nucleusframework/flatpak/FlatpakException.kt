package dev.nucleusframework.flatpak

/** Why a Flatpak call failed. */
public enum class FlatpakErrorCode {
    /** Not Linux, or the native bridge is missing. */
    Unsupported,

    /** The app does not run in a Flatpak sandbox (see [FlatpakAvailability.NotFlatpak]). */
    NotFlatpak,

    /**
     * The Flatpak portal (`flatpak-portal`, version 2 or later) or the Access portal behind its
     * update dialog is missing, or went away.
     */
    PortalMissing,

    /**
     * The user answered "Deny" to the portal's "Update …?" dialog. Flatpak remembers the answer:
     * it can only be changed from the desktop's privacy settings (GNOME: Apps, the app's page).
     */
    UpdateDenied,

    /**
     * The new version asks for permissions the installed one does not have, which only the store
     * or `flatpak update` may grant: send the user to [FlatpakUpdates.openStorePage].
     */
    NewPermissionsRequired,

    /** [FlatpakUpdates.install] is already running in this process. */
    InProgress,

    /** Any other error of the portal; [FlatpakException.portalError] carries the D-Bus error. */
    Platform,

    /** A bug in this module or its native bridge. */
    Internal,
}

/**
 * A failed Flatpak call.
 *
 * @property portalError the D-Bus error name the portal answered with, `null` when the failure
 *   did not come from the portal
 */
public class FlatpakException(
    public val code: FlatpakErrorCode,
    message: String,
    public val portalError: String? = null,
    cause: Throwable? = null,
) : Exception(message, cause) {
    override fun toString(): String =
        buildString {
            append("FlatpakException(").append(code)
            portalError?.let { append(", ").append(it) }
            append("): ").append(message)
        }
}
