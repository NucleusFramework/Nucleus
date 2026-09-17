package dev.nucleusframework.window.tao.workspace

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.IntSize
import dev.nucleusframework.window.tao.DockSide
import dev.nucleusframework.window.tao.TaoWindow
import dev.nucleusframework.window.tao.edgeStripPx

/**
 * What a drop target inside a window publishes about itself: the window, the
 * target's bounds in that window and the content size those bounds were
 * measured against — enough to place the target on screen (physical px) and
 * hit-test a pointer against it, whichever window that pointer is over.
 *
 * Geometry is read through lambdas so tests can stand in for the native window.
 */
internal class HostGeometry(
    val host: TaoWindow,
    val outerBoundsPx: () -> LongArray? = host::outerBoundsPx,
    val scaleFactor: () -> Float = { host.scaleFactor },
    /** Whether the host is minimized: its frame is still on record, but nothing of it is on screen. */
    val minimized: () -> Boolean = { host.isMinimized },
) {
    /** The target's bounds in the host window (physical px). */
    var layoutBoundsInWindowPx: Rect = Rect.Zero

    /** The host's content size when [layoutBoundsInWindowPx] was captured. */
    var containerSizePx: IntSize = IntSize.Zero

    /**
     * The drop zones the target offers right now, in the host window
     * (physical px) — exactly the rectangles it draws while a drag is in
     * flight, so what a drag is hit-tested against is what the user sees.
     * Empty while nothing is being dragged, or for a target that publishes
     * none; the hit test then falls back to the edges of
     * [layoutBoundsInWindowPx].
     */
    var zoneBoundsInWindowPx: Map<DockSide, DockDropZone> = emptyMap()

    /** Physical pixels per dp on the host, `1` while the window has none yet. */
    fun scaleOrOne(): Float = scaleFactor().takeIf { it > 0f } ?: 1f

    /**
     * Screen position of the host's content origin, `null` before the first
     * layout, while unmapped, or on a host whose screen position is not
     * knowable ([canPlaceOnScreen] — native Wayland), where the origin
     * GDK reports would place every window at the top-left of the screen.
     */
    fun clientOriginPx(): Offset? {
        if (containerSizePx == IntSize.Zero || !host.canPlaceOnScreen) return null
        val outer = outerBoundsPx() ?: return null
        return clientOriginPx(outer, containerSizePx)
    }

    /** The target's rect on screen (physical px), `null` while [clientOriginPx] is. */
    fun layoutScreenRectPx(): Rect? = clientOriginPx()?.let { layoutBoundsInWindowPx.translate(it) }

    /**
     * The drop zones on screen (physical px): the published
     * [zoneBoundsInWindowPx], else a strip of [zoneWidthPx] inside each edge
     * of the layout — the same four zones the pointer hit test uses. `null`
     * while [clientOriginPx] is.
     */
    fun zoneScreenRectsPx(zoneWidthPx: Float): Map<DockSide, DockDropZone>? {
        val origin = clientOriginPx() ?: return null
        if (zoneBoundsInWindowPx.isNotEmpty()) {
            return zoneBoundsInWindowPx.mapValues { (_, zone) -> zone.translate(origin) }
        }
        val rect = layoutBoundsInWindowPx.translate(origin)
        return DockSide.entries.associateWith { side -> DockDropZone(edgeStripPx(rect, side, zoneWidthPx)) }
    }
}

/**
 * What one side of a drop target offers a drag, in whichever px space the
 * holder says: the [strip] a satellite enters the side by, and — when panels
 * are already docked there — one [slots] rect per rank the dropped panel can
 * take among them, in rank order, covering the stack and the strip between
 * them. Empty [slots] mean the side has no panel to order against.
 */
internal data class DockDropZone(
    val strip: Rect,
    val slots: List<Rect> = emptyList(),
) {
    fun translate(offset: Offset): DockDropZone =
        DockDropZone(strip.translate(offset), slots.map { it.translate(offset) })

    /** Whether [point] is on the strip or on one of the slots. */
    fun contains(point: Offset): Boolean = strip.contains(point) || slots.any { it.contains(point) }

    /**
     * The rank [point] aims at: the slot it is in, else the nearest one, so a
     * pointer past either end of the stack means its first or last rank.
     * `null` without slots: nothing to order against. An empty slot is a rank
     * that is not on offer (it would displace a pinned panel) and is skipped.
     */
    fun slotAt(point: Offset): Int? =
        slots.indices
            .filter { !slots[it].isEmpty }
            .minByOrNull { distanceSquaredPx(slots[it], point) }

    private fun distanceSquaredPx(
        rect: Rect,
        point: Offset,
    ): Float {
        val dx = maxOf(rect.left - point.x, 0f, point.x - rect.right)
        val dy = maxOf(rect.top - point.y, 0f, point.y - rect.bottom)
        return dx * dx + dy * dy
    }
}

/**
 * The published geometry of every host in a group: one per window, the latest
 * publisher winning, an unregister only taking effect for the geometry that is
 * still registered (two layouts swapping in one window must not unregister
 * each other).
 */
internal class HostGeometryRegistry {
    private val geometries = LinkedHashMap<TaoWindow, HostGeometry>()

    fun register(geometry: HostGeometry) {
        geometries[geometry.host] = geometry
    }

    fun unregister(geometry: HostGeometry) {
        if (geometries[geometry.host] === geometry) geometries.remove(geometry.host)
    }

    operator fun get(host: TaoWindow?): HostGeometry? = host?.let(geometries::get)

    /**
     * Every geometry, in the order [hosts] lists their windows (hosts without
     * one skipped), then the ones [hosts] does not name in registration order.
     * The caller decides what "first" means — the owner, focus recency, z-order.
     */
    fun ordered(hosts: List<TaoWindow>): List<HostGeometry> {
        val ordered = ArrayList<HostGeometry>(geometries.size)
        for (host in hosts) geometries[host]?.let(ordered::add)
        for (geometry in geometries.values) if (geometry !in ordered) ordered += geometry
        return ordered
    }
}

/** The host's side borders are assumed symmetric: half the outer/inner width difference each. */
private const val SIDE_BORDER_SPLIT = 2f

/**
 * Screen position (physical px) of a window's content origin, derived from its
 * outer frame `[x, y, w, h]` and its content size.
 *
 * Side borders are split evenly, the bottom border is assumed to match them,
 * and whatever vertical difference is left sits above the content — a title
 * bar, the top margin of a client-side-decorated shadow.
 *
 * Attributing the bottom border rather than putting the whole vertical
 * difference on top is what makes this right on Win32, whose `GetWindowRect`
 * includes the invisible resize border below the content as well as beside it:
 * a pointer aimed through a frame modelled as "all chrome on top" lands one
 * border too low, which is enough to miss the bottom of a tab. It is a no-op
 * for a frame that adds nothing horizontally (a Tao window on X11), and stays
 * exact for a symmetric shadow and for macOS's title bar.
 */
@Suppress("MagicNumber")
internal fun clientOriginPx(
    outer: LongArray,
    containerSizePx: IntSize,
): Offset {
    val sideBorder = (outer[2] - containerSizePx.width) / SIDE_BORDER_SPLIT
    return Offset(
        outer[0] + sideBorder,
        outer[1] + (outer[3] - containerSizePx.height) - sideBorder,
    )
}

/**
 * A [HostGeometry] for [host], registered with [registry] for as long as the
 * caller is composed. `null` without a host (a preview, a test composition).
 */
@Composable
internal fun rememberHostGeometry(
    registry: HostGeometryRegistry,
    host: TaoWindow?,
): HostGeometry? {
    val geometry = remember(registry, host) { host?.let { HostGeometry(it) } }
    if (geometry != null) {
        DisposableEffect(registry, geometry) {
            registry.register(geometry)
            onDispose { registry.unregister(geometry) }
        }
    }
    return geometry
}

/**
 * Publishes this element's bounds into [geometry] on every placement, together
 * with the window content size ([containerSizePx]) they were measured in.
 * A no-op without a geometry.
 */
internal fun Modifier.publishHostGeometry(
    geometry: HostGeometry?,
    containerSizePx: IntSize,
): Modifier =
    if (geometry == null) {
        this
    } else {
        onGloballyPositioned { coordinates ->
            geometry.layoutBoundsInWindowPx = coordinates.boundsInWindow()
            geometry.containerSizePx = containerSizePx
        }
    }
