package dev.nucleusframework.tray.utils

import dev.nucleusframework.tray.InternalNucleusTrayApi
import java.util.Collections
import java.util.concurrent.atomic.AtomicReference

/** A tray-icon click position in the native backend's coordinates (physical px on Windows, logical elsewhere). */
@InternalNucleusTrayApi
public data class TrayClickPoint(
    public val x: Int,
    public val y: Int,
)

/**
 * Records the last tray-icon click reported by the native backends.
 *
 * The core tray itself doesn't need screen geometry; only `TrayApp`'s popup does. So the native
 * managers record the raw click coordinates here and the `TrayApp` (`dev.nucleusframework.tray.app`) reads them
 * to compute the popup position against the (Tao-backed) screen geometry it owns.
 */
@InternalNucleusTrayApi
public object TrayClickTracker {
    private val last = AtomicReference<TrayClickPoint?>(null)
    private val perInstance: MutableMap<String, TrayClickPoint> =
        Collections.synchronizedMap(mutableMapOf())

    internal fun recordClick(
        x: Int,
        y: Int,
    ) {
        last.set(TrayClickPoint(x, y))
    }

    internal fun recordClick(
        instanceId: String,
        x: Int,
        y: Int,
    ) {
        val point = TrayClickPoint(x, y)
        perInstance[instanceId] = point
        last.set(point)
    }

    /** Last click on any tray icon, or null. */
    public fun lastClick(): TrayClickPoint? = last.get()

    /** Last click on the tray icon [instanceId], or null. */
    public fun lastClick(instanceId: String): TrayClickPoint? = perInstance[instanceId]
}
