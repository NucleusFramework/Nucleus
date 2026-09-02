package dev.nucleusframework.window.tao

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.nucleusframework.window.tao.workspace.DragController
import dev.nucleusframework.window.tao.workspace.HostGeometry
import dev.nucleusframework.window.tao.workspace.HostGeometryRegistry
import dev.nucleusframework.window.tao.workspace.RelocatableSlot
import dev.nucleusframework.window.tao.workspace.WindowGroup
import dev.nucleusframework.window.tao.workspace.clientOriginPx
import dev.nucleusframework.window.tao.workspace.sanitizedOrNull

/**
 * One satellite known to a [SatelliteWorkspace]: identity, placement and the
 * live geometry of its floating window.
 *
 * Created by [Satellite] on first composition (or by
 * [SatelliteWorkspace.restore] ahead of it) and kept for the lifetime of the
 * workspace, so a satellite the app takes out of composition and brings back
 * resumes where it was.
 */
public class SatelliteEntry internal constructor(
    /** Stable identity, the key used by every [SatelliteWorkspace] operation. */
    public val id: String,
    title: String,
    initialPlacement: SatellitePlacement,
    isOpen: Boolean,
) {
    /** Human-readable title, shown by the default header. */
    public var title: String by mutableStateOf(title)
        internal set

    /** Where the satellite currently lives. */
    public var placement: SatellitePlacement by mutableStateOf(initialPlacement)
        internal set

    /** `false` once the user (or the app) closed the satellite; reopen with [SatelliteWorkspace.open]. */
    public var isOpen: Boolean by mutableStateOf(isOpen)
        internal set

    /**
     * The window whose [DockLayout] hosts this satellite while it is docked.
     * `null` while floating, and while docked with no workspace member to
     * dock into yet — the next window to join picks it up.
     */
    public var dockHost: TaoWindow? by mutableStateOf(null)
        internal set

    /** `true` while [placement] is [SatellitePlacement.Docked]. */
    public val isDocked: Boolean get() = placement is SatellitePlacement.Docked

    /** The side [SatelliteScope.dock] targets when none is given: the last docked side. */
    public var preferredDockSide: DockSide by
        mutableStateOf((initialPlacement as? SatellitePlacement.Docked)?.side ?: DockSide.Right)
        internal set

    /**
     * Geometry of the floating window: size, placement rule and the live
     * offset from the owner. Meaningful while [placement] is
     * [SatellitePlacement.Floating]; the values are also what
     * [SatelliteWorkspace.undock] falls back to.
     */
    public val windowState: SatelliteWindowState =
        floatingOf(initialPlacement).let { SatelliteWindowState(it.size, it.positioner, it.anchorRect) }

    /** Floating geometry to return to when undocking without a lift-off rect. */
    internal var lastFloating: SatellitePlacement.Floating = floatingOf(initialPlacement)

    internal var content: (@Composable SatelliteScope.() -> Unit)? by mutableStateOf(null)
    internal var header: (@Composable SatelliteScope.() -> Unit)? by mutableStateOf(null)

    /** `rememberSaveable` values carried across a dock / undock host change. */
    internal val stateSlot: RelocatableSlot = RelocatableSlot()

    /** Last docked panel rect in the host's window coordinates (physical px). */
    internal var dockedBoundsInWindowPx: Rect? = null

    /** The host's content size (physical px) when [dockedBoundsInWindowPx] was captured. */
    internal var dockHostContainerSizePx: IntSize? = null

    private companion object {
        fun floatingOf(placement: SatellitePlacement): SatellitePlacement.Floating =
            placement as? SatellitePlacement.Floating ?: SatellitePlacement.Floating()
    }
}

/**
 * Per-satellite part of a [SatelliteLayoutSnapshot].
 *
 * @property placement where the satellite was; a floating placement carries
 *   the user's last position baked into its positioner.
 * @property isOpen whether it was open.
 */
public data class SatelliteSnapshot(
    val placement: SatellitePlacement,
    val isOpen: Boolean,
)

/**
 * Serializable-by-the-app picture of a [SatelliteWorkspace] layout: every
 * satellite's placement and open state plus the dock extents. Produce it with
 * [SatelliteWorkspace.snapshot], apply it with [SatelliteWorkspace.restore].
 *
 * @property satellites snapshots keyed by satellite id.
 * @property dockExtents width (left/right) or height (top/bottom) of each dock side.
 */
public data class SatelliteLayoutSnapshot(
    val satellites: Map<String, SatelliteSnapshot>,
    val dockExtents: Map<DockSide, Dp>,
)

/**
 * The set of satellites shared by a group of windows, and the rules that bind
 * them together.
 *
 * Windows **join** the workspace ([JoinSatelliteWorkspace]); satellites are
 * **declared** against it ([Satellite]) and hosted according to their
 * [SatellitePlacement]:
 *
 *  - **Owner.** Floating satellites are owned by, anchored to and follow the
 *    workspace's [owner]: the most recently focused member when [followFocus]
 *    is on (the default), or the member pinned with [pinTo]. When the owner
 *    closes, the previously focused member takes over and the satellites move
 *    on without changing their position on screen. One palette can serve any
 *    number of document windows this way — no reparenting call needed.
 *  - **Docking.** [dock] turns a floating satellite into a panel inside the
 *    owner's [DockLayout]; [undock] lifts it back out as a window placed
 *    exactly where the panel was. `rememberSaveable` state inside the
 *    satellite survives both moves.
 *  - **Collective state.** [visible] hides and restores every satellite at
 *    once (the "Tab hides all palettes" gesture); [snapshot] / [restore]
 *    capture the whole layout for the app to persist.
 *
 * Every member of this class is meant for the Tao event-loop thread, which is
 * also the Compose dispatcher.
 *
 * @param followFocus when `true`, the owner follows keyboard focus between
 *   members; when `false`, it is the pinned member or the first to have joined.
 */
@Suppress("TooManyFunctions")
public class SatelliteWorkspace(
    public val followFocus: Boolean = true,
) {
    private val group =
        WindowGroup(
            followFocus = followFocus,
            onJoined = { window ->
                // Docked satellites left without a host by an earlier member's
                // departure (or restored before any window joined) land here.
                for (entry in entryMap.values) {
                    if (entry.isDocked && entry.dockHost == null) entry.dockHost = window
                }
            },
            onLeft = { window, fallback ->
                for (entry in entryMap.values) {
                    if (entry.dockHost === window) entry.dockHost = fallback
                }
            },
        )

    /** The member [pinTo] selected as owner, or `null` when the owner is chosen by focus. */
    public val pinnedOwner: TaoWindow? get() = group.pinned

    /** Windows that have joined, in join order. */
    public val members: List<TaoWindow> get() = group.members

    /**
     * The window floating satellites currently belong to, or `null` while no
     * member has joined. Pinned member first, then the most recently focused
     * member (with [followFocus]), then the first member.
     */
    public val owner: TaoWindow? get() = group.owner

    private val entryMap = mutableStateMapOf<String, SatelliteEntry>()

    /** Every satellite declared so far, including closed ones. */
    public val satellites: Collection<SatelliteEntry> get() = entryMap.values

    /** The satellite registered under [id], if any. */
    public fun satellite(id: String): SatelliteEntry? = entryMap[id]

    /** Master switch: `false` hides every satellite, floating and docked alike, without closing any. */
    public var visible: Boolean by mutableStateOf(true)

    private val extents = mutableStateMapOf<DockSide, Dp>()
    private val pendingRestore = HashMap<String, SatelliteSnapshot>()

    /** Width (left/right) or height (top/bottom) of the panels docked on [side]. */
    public fun dockExtent(side: DockSide): Dp = extents[side] ?: DefaultDockExtent

    /**
     * The extent [side] would have once [entry] is docked there: the side's
     * own extent when it already has one, else the satellite's floating size,
     * which is what the first drop seeds it with. [DockLayout] previews a drop
     * at this width rather than at the default one it has not adopted yet.
     */
    public fun plannedDockExtent(
        entry: SatelliteEntry,
        side: DockSide,
    ): Dp =
        extents[side] ?: entry.windowState.size
            .let { if (side.isVertical) it.width else it.height }
            .coerceAtLeast(MinDockExtent)

    /** Sets [dockExtent]; clamped to [MinDockExtent]. Driven by the [DockLayout] splitters. */
    public fun setDockExtent(
        side: DockSide,
        extent: Dp,
    ) {
        extents[side] = extent.coerceAtLeast(MinDockExtent)
    }

    // ── Members ──────────────────────────────────────────────────────────

    /**
     * Adds [window] to the workspace. Idempotent. Prefer [JoinSatelliteWorkspace]
     * from the window's content; it leaves again when that content is disposed.
     */
    public fun join(window: TaoWindow) {
        group.join(window)
    }

    /**
     * Removes [window] from the workspace. Called automatically when a member
     * is destroyed. Satellites docked into it move to the next [owner].
     */
    public fun leave(window: TaoWindow) {
        group.leave(window)
    }

    /** Records [window] as the most recently focused member. */
    internal fun noteFocus(window: TaoWindow) {
        group.noteFocus(window)
    }

    /**
     * Makes [window] the [owner] regardless of focus; `null` goes back to the
     * focus-driven choice. A pinned window that is not (or no longer) a member
     * is ignored.
     */
    public fun pinTo(window: TaoWindow?) {
        group.pinTo(window)
    }

    // ── Satellites ───────────────────────────────────────────────────────

    /** Shows the satellite [id] again after [close]. */
    public fun open(id: String) {
        entryMap[id]?.isOpen = true
    }

    /** Hides the satellite [id] until [open]; its placement and state are kept. */
    public fun close(id: String) {
        entryMap[id]?.isOpen = false
    }

    /** [open] or [close], whichever applies. */
    public fun toggle(id: String) {
        entryMap[id]?.let { it.isOpen = !it.isOpen }
    }

    /**
     * Docks the satellite [id] on [side] of a [DockLayout]: the one in [host]
     * when given, else — for a satellite already docked — the host it is in,
     * else the current [owner]'s. [order] positions it among the panels on
     * that side; `null` appends it after them. The first satellite docked on a
     * side seeds that side's [dockExtent] from its floating size.
     */
    public fun dock(
        id: String,
        side: DockSide,
        order: Int? = null,
        host: TaoWindow? = null,
    ) {
        val entry = entryMap[id] ?: return
        val current = entry.placement
        if (current is SatellitePlacement.Floating) {
            entry.lastFloating = currentFloating(entry, current)
            if (side !in extents) setDockExtent(side, plannedDockExtent(entry, side))
        }
        entry.placement = SatellitePlacement.Docked(side, order ?: nextOrder(side, exclude = entry))
        entry.preferredDockSide = side
        entry.dockHost =
            host?.takeIf { it in members }
                ?: entry.dockHost?.takeIf { it in members }
                ?: owner
    }

    /**
     * Turns the docked satellite [id] back into a floating window: at
     * [placement] when given, else over the panel it just was when the host's
     * geometry is known, else at its last floating position. No-op for a
     * floating satellite.
     */
    public fun undock(
        id: String,
        placement: SatellitePlacement.Floating? = null,
    ) {
        val entry = entryMap[id] ?: return
        val docked = entry.placement as? SatellitePlacement.Docked ?: return
        entry.preferredDockSide = docked.side
        applyFloating(entry, placement ?: liftOffPlacement(entry) ?: entry.lastFloating)
    }

    // ── Drag and drop ────────────────────────────────────────────────────

    /** The [DockLayout] geometry every member publishes, for hit-testing and lift-off placement. */
    internal val dockHosts: HostGeometryRegistry = HostGeometryRegistry()

    private val drags =
        DragController<SatelliteDragSession> {
            draggedSatellite = null
            dockPreview = null
            dragGhost = null
        }

    /**
     * The satellite being dragged right now, or `null`. While it is set every
     * [DockLayout] in the workspace shows where the satellite can be dropped,
     * which is what makes the gesture discoverable.
     */
    public var draggedSatellite: SatelliteEntry? by mutableStateOf(null)
        internal set

    /**
     * The dock zone the satellite being dragged would land in if released
     * now, or `null`. [DockLayout] highlights it in the target window; custom
     * layouts may read it for their own preview.
     */
    public var dockPreview: DockTarget? by mutableStateOf(null)
        internal set

    /**
     * The translucent preview of a panel being dragged out of its dock, or
     * `null`. [Satellite] shows it as a borderless window that follows the
     * pointer, so tearing a panel out of a window is something you can see
     * leaving the window.
     */
    public var dragGhost: DragGhost? by mutableStateOf(null)
        internal set

    /** The drag currently owning the feedback state, or `null`. */
    internal val activeDragSession: SatelliteDragSession? get() = drags.active

    /** `true` while [session] is the one the workspace is publishing. */
    internal fun isLiveDrag(session: SatelliteDragSession): Boolean = drags.isLive(session)

    /** Ends [session] if it is live (`null`: whichever is) and clears everything a drag publishes. Idempotent. */
    internal fun releaseDrag(session: SatelliteDragSession?) {
        drags.release(session)
    }

    internal fun dockHostGeometry(host: TaoWindow?): HostGeometry? = dockHosts[host]

    /**
     * The dock zone under [screenPx] (physical screen pixels): the strip of
     * [DockZoneWidth] inside each edge of a member's [DockLayout], the nearest
     * edge winning where two overlap. Where windows overlap on screen, the
     * [owner]'s layout is tried first, then the others by focus recency — the
     * window the user worked in last is the one most likely on top. A
     * minimized member is never a target: its frame is still on record, but
     * nothing of it is on screen to drop onto. `null` over content or outside
     * every layout.
     */
    public fun dockTargetAt(screenPx: Offset): DockTarget? {
        val hit =
            dockHosts
                .ordered(group.membersByRecency)
                .asSequence()
                .filter { !it.minimized() }
                .firstNotNullOfOrNull { it.dockHitTest(screenPx, DockZoneWidth) }
        return (hit as? DockHit.Zone)?.target
    }

    /**
     * Starts dragging the satellite [id] from [origin], with the pointer at
     * [pointerScreenPx] (physical screen pixels). Feed the session the pointer
     * as it moves and release it with [SatelliteDragSession.end]; it moves a
     * floating window along, publishes [dockPreview] / [dragGhost], and docks,
     * re-docks or undocks on release. `null` when [id] is unknown or the
     * origin's geometry is not available.
     *
     * [Modifier.satelliteDragHandle] drives this from a pointer gesture; call
     * it directly to drive docking from another input source.
     */
    public fun beginDrag(
        id: String,
        origin: SatelliteDragOrigin,
        pointerScreenPx: Offset,
    ): SatelliteDragSession? {
        val entry = entryMap[id] ?: return null
        val start = pointerScreenPx.sanitizedOrNull() ?: return null
        // Whatever was dragging until now is over: two live sessions would
        // fight over the same published state.
        val session = createDragSession(entry, origin, start) ?: return null
        drags.begin(session)
        draggedSatellite = entry
        return session
    }

    /** Floating placement whose window's top-left lands at [screenTopLeftPx], relative to the current [owner]. */
    internal fun floatingAtScreen(
        screenTopLeftPx: Offset,
        sizePx: Size,
    ): SatellitePlacement.Floating? {
        val owner = owner ?: return null
        val outer = dockHosts[owner]?.outerBoundsPx() ?: owner.outerBoundsPx() ?: return null
        val scale = (dockHosts[owner]?.scaleFactor() ?: owner.scaleFactor).takeIf { it > 0f } ?: 1f
        return SatellitePlacement.Floating(
            positioner =
                offsetPositioner(
                    DpOffset(((screenTopLeftPx.x - outer[0]) / scale).dp, ((screenTopLeftPx.y - outer[1]) / scale).dp),
                ),
            size = DpSize((sizePx.width / scale).dp, (sizePx.height / scale).dp),
        )
    }

    // ── Layout persistence ───────────────────────────────────────────────

    /** Captures every satellite's placement and open state, plus the dock extents. */
    public fun snapshot(): SatelliteLayoutSnapshot =
        SatelliteLayoutSnapshot(
            satellites =
                pendingRestore.toMap() +
                    entryMap.mapValues { (_, entry) ->
                        val placement = entry.placement
                        val stored =
                            if (placement is SatellitePlacement.Floating) {
                                currentFloating(entry, placement)
                            } else {
                                placement
                            }
                        SatelliteSnapshot(stored, entry.isOpen)
                    },
            dockExtents = extents.toMap(),
        )

    /**
     * Applies [snapshot]. Satellites it names that are not declared yet are
     * applied when they are; satellites it does not name are left alone.
     */
    public fun restore(snapshot: SatelliteLayoutSnapshot) {
        extents.clear()
        // Through the setter: a snapshot written by an older version — or by
        // hand — must not be able to install an extent below the minimum and
        // leave a splitter no one can grab.
        for ((side, extent) in snapshot.dockExtents) setDockExtent(side, extent)
        for ((id, saved) in snapshot.satellites) {
            val entry = entryMap[id]
            if (entry == null) pendingRestore[id] = saved else apply(entry, saved)
        }
    }

    // ── Registration (driven by the Satellite composable) ────────────────

    internal fun register(
        id: String,
        title: String,
        initialPlacement: SatellitePlacement,
        initiallyOpen: Boolean,
    ): SatelliteEntry {
        entryMap[id]?.let {
            it.title = title
            return it
        }
        val entry = SatelliteEntry(id, title, initialPlacement, initiallyOpen)
        if (initialPlacement is SatellitePlacement.Docked) entry.dockHost = owner
        entryMap[id] = entry
        pendingRestore.remove(id)?.let { apply(entry, it) }
        return entry
    }

    internal fun unregister(entry: SatelliteEntry) {
        entry.content = null
        entry.header = null
    }

    // ── Internals ────────────────────────────────────────────────────────

    private fun apply(
        entry: SatelliteEntry,
        saved: SatelliteSnapshot,
    ) {
        entry.isOpen = saved.isOpen
        when (val placement = saved.placement) {
            is SatellitePlacement.Floating -> {
                applyFloating(entry, placement)
                // Already on screen: move it, since placement is otherwise one-shot.
                entry.windowState.reanchor()
            }
            is SatellitePlacement.Docked -> {
                val current = entry.placement
                if (current is SatellitePlacement.Floating) entry.lastFloating = currentFloating(entry, current)
                entry.placement = placement
                entry.preferredDockSide = placement.side
                entry.dockHost = owner
            }
        }
    }

    private fun applyFloating(
        entry: SatelliteEntry,
        floating: SatellitePlacement.Floating,
    ) {
        entry.lastFloating = floating
        entry.windowState.size = floating.size
        entry.windowState.positioner = floating.positioner
        entry.windowState.anchorRect = floating.anchorRect
        entry.windowState.offsetFromParent = null
        entry.placement = floating
        entry.dockHost = null
    }

    /**
     * The floating placement that reproduces where the satellite *is*: the
     * user's dragged offset baked into a top-left positioner, else the rule
     * it was declared with.
     */
    private fun currentFloating(
        entry: SatelliteEntry,
        declared: SatellitePlacement.Floating,
    ): SatellitePlacement.Floating {
        val offset = entry.windowState.offsetFromParent
        val positioner =
            if (offset != null) {
                offsetPositioner(offset)
            } else {
                entry.windowState.positioner
            }
        return SatellitePlacement.Floating(
            positioner = positioner,
            size = entry.windowState.size,
            anchorRect = if (offset != null) null else declared.anchorRect,
        )
    }

    /**
     * Where the docked panel sits on screen, as a floating placement, so the
     * undocked window appears to lift off the panel. `null` when the host's
     * geometry is not available.
     *
     * The host's client origin is derived from its outer frame and content
     * size (side borders split evenly, everything else on top), which is
     * exact for Tao's client-side-decorated windows and off by at most a
     * shadow margin elsewhere.
     */
    private fun liftOffPlacement(entry: SatelliteEntry): SatellitePlacement.Floating? {
        val host = entry.dockHost ?: return null
        val bounds = entry.dockedBoundsInWindowPx ?: return null
        val container = entry.dockHostContainerSizePx ?: return null
        val outer = (dockHosts[host]?.outerBoundsPx() ?: host.outerBoundsPx()) ?: return null
        val scale = (dockHosts[host]?.scaleFactor() ?: host.scaleFactor).takeIf { it > 0f } ?: 1f
        val client = clientOriginPx(outer, container)
        val dx = (client.x + bounds.left - outer[0]) / scale
        val dy = (client.y + bounds.top - outer[1]) / scale
        return SatellitePlacement.Floating(
            positioner = offsetPositioner(DpOffset(dx.dp, dy.dp)),
            size = DpSize((bounds.width / scale).dp, (bounds.height / scale).dp),
        )
    }

    private fun nextOrder(
        side: DockSide,
        exclude: SatelliteEntry,
    ): Int =
        entryMap.values
            .filter { it !== exclude }
            .mapNotNull { (it.placement as? SatellitePlacement.Docked)?.takeIf { d -> d.side == side }?.order }
            .maxOrNull()
            ?.plus(1) ?: 0

    /** Constants shared with [DockLayout]. */
    public companion object {
        /** Extent a dock side gets before any satellite seeded it. */
        public val DefaultDockExtent: Dp = 280.dp

        /** Smallest extent a dock side can be dragged or set to. */
        public val MinDockExtent: Dp = 80.dp

        /** Depth of the drop zone inside each edge of a [DockLayout]. */
        public val DockZoneWidth: Dp = 64.dp

        /** Pins the satellite's top-left corner at [offset] from the owner's, sliding on-screen if needed. */
        internal fun offsetPositioner(offset: DpOffset): WindowPositioner =
            WindowPositioner(
                parentAnchor = WindowAnchor.TopLeft,
                childAnchor = WindowAnchor.TopLeft,
                offset = offset,
                constraintAdjustment = WindowConstraintAdjustment.Slide,
            )
    }
}

/** A dock zone: the [side] of the [DockLayout] in [host]. */
public data class DockTarget(
    val host: TaoWindow,
    val side: DockSide,
)

/**
 * The preview of a satellite being dragged out of its dock: which satellite,
 * and where it sits on screen right now (physical screen pixels, outer frame
 * of the ghost window).
 */
public data class DragGhost(
    val satellite: SatelliteEntry,
    val screenRectPx: Rect,
    /**
     * Physical pixels per dp on the host the panel came from. The rect is in
     * physical screen pixels; a window is placed in logical ones, and the
     * application scope the ghost is composed in has no density of its own.
     */
    val scaleFactor: Float,
)

/** Where a satellite drag starts; see [SatelliteWorkspace.beginDrag]. */
public sealed interface SatelliteDragOrigin {
    /**
     * The satellite's own floating window, dragged by its header. The window
     * follows the pointer through [move] (outer top-left, physical px).
     */
    public class FloatingWindow internal constructor(
        public val window: TaoWindow,
        internal val outerBoundsPx: () -> LongArray?,
        internal val move: (xPx: Int, yPx: Int) -> Unit,
    ) : SatelliteDragOrigin {
        public constructor(window: TaoWindow) : this(window, window::outerBoundsPx, window::setOuterPositionPx)
    }

    /** The satellite's docked panel in [host], dragged by its header. */
    public class DockedPanel(
        public val host: TaoWindow,
    ) : SatelliteDragOrigin
}

/**
 * A satellite drag in progress. Positions are physical screen pixels.
 * Obtained from [SatelliteWorkspace.beginDrag].
 *
 * A session stops acting the moment it is no longer the workspace's current
 * drag — cancelled, finished, or superseded by another [SatelliteWorkspace.beginDrag].
 * Every method is then a no-op, so a late release from an abandoned gesture
 * cannot move a window or re-dock a satellite. All three are safe to call
 * repeatedly and in any order.
 *
 * Positions that are not finite (an `Offset.Unspecified` from a detached
 * layout, an infinity) are ignored rather than propagated into window
 * geometry; the last usable position stands.
 */
public interface SatelliteDragSession {
    /** The pointer moved. */
    public fun update(pointerScreenPx: Offset)

    /** The pointer was released: dock, re-dock or undock according to where. */
    public fun end(pointerScreenPx: Offset)

    /** The gesture was abandoned: nothing changes placement. */
    public fun cancel()
}

/**
 * Where [screenPx] falls on this [DockLayout] geometry: `null` outside it,
 * [DockHit.Content] inside but clear of the edges, [DockHit.Zone] within
 * [zoneWidth] of the nearest edge.
 */
internal fun HostGeometry.dockHitTest(
    screenPx: Offset,
    zoneWidth: Dp,
): DockHit? {
    val rect = layoutScreenRectPx() ?: return null
    if (!rect.contains(screenPx)) return null
    val zonePx = zoneWidth.value * scaleFactor()
    val (side, distance) =
        listOf(
            DockSide.Left to screenPx.x - rect.left,
            DockSide.Right to rect.right - screenPx.x,
            DockSide.Top to screenPx.y - rect.top,
            DockSide.Bottom to rect.bottom - screenPx.y,
        ).minBy { it.second }
    return if (distance <= zonePx) DockHit.Zone(DockTarget(host, side)) else DockHit.Content
}

/** Result of [dockHitTest]. */
internal sealed interface DockHit {
    /** Inside the layout, over the content: not a drop target, but no other layout is consulted. */
    data object Content : DockHit

    /** Inside a dock zone. */
    data class Zone(
        val target: DockTarget,
    ) : DockHit
}

/** Remembers a [SatelliteWorkspace] for the lifetime of the calling composition. */
@Composable
public fun rememberSatelliteWorkspace(followFocus: Boolean = true): SatelliteWorkspace =
    remember { SatelliteWorkspace(followFocus) }

/**
 * Makes the enclosing window (or [window]) a member of [workspace] for as long
 * as this composable is in composition. Call it from the window's content,
 * typically right under [DecoratedWindow].
 */
@Composable
public fun JoinSatelliteWorkspace(
    workspace: SatelliteWorkspace,
    window: TaoWindow? = LocalTaoWindow.current,
) {
    DisposableEffect(workspace, window) {
        if (window == null) return@DisposableEffect onDispose {}
        workspace.join(window)
        onDispose { workspace.leave(window) }
    }
}
