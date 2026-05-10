package dev.nucleusframework.nucleus.launcher.linux

/**
 * Properties that can be set on a Unity Launcher entry.
 *
 * Only non-null properties are included in the D-Bus Update signal.
 * The spec requires that _only changed properties_ are sent.
 *
 * @property count Badge count displayed on the launcher icon.
 * @property countVisible Whether the count badge is visible.
 * @property progress Progress bar value in the range `0.0..1.0`.
 * @property progressVisible Whether the progress bar is visible.
 * @property urgent Whether the launcher entry requests user attention.
 * @property quicklist D-Bus object path to a `com.canonical.dbusmenu` server instance,
 *   or an empty string to unset the quicklist.
 * @property updating Whether the application is currently being updated.
 */
data class LauncherProperties(
    val count: Long? = null,
    val countVisible: Boolean? = null,
    val progress: Double? = null,
    val progressVisible: Boolean? = null,
    val urgent: Boolean? = null,
    val quicklist: String? = null,
    val updating: Boolean? = null,
)
