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
import androidx.compose.ui.unit.roundToIntRect
import dev.nucleusframework.window.tao.workspace.DockDropZone
import dev.nucleusframework.window.tao.workspace.DragController
import dev.nucleusframework.window.tao.workspace.HostGeometry
import dev.nucleusframework.window.tao.workspace.HostGeometryRegistry
import dev.nucleusframework.window.tao.workspace.RelocatableSlot
import dev.nucleusframework.window.tao.workspace.TransferGhostSource
import dev.nucleusframework.window.tao.workspace.WindowGroup
import dev.nucleusframework.window.tao.workspace.clientOriginPx
import dev.nucleusframework.window.tao.workspace.sanitizedOrNull
import dev.nucleusframework.window.tao.workspace.warnScreenPlacementUnsupported
import kotlin.math.abs

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
    /**
     * The sides this satellite may be docked on. Every other side is neither
     * offered to a drag nor accepted by [SatelliteWorkspace.dock]; empty means
     * the satellite only ever floats. Declared with [Satellite].
     */
    public val dockSides: Set<DockSide> = DockSide.entries.toSet(),
    /**
     * Whether this satellite can be a window of its own. `false` is a fixed
     * panel: [SatelliteWorkspace.undock] refuses it, a drag can only move it
     * within the dock, and a restore never floats it. Declared with [Satellite].
     */
    public val isFloatable: Boolean = true,
    /**
     * Whether the user may change this satellite's rank on its side. `false`
     * pins it to the rank it was declared with: its own drag offers it none,
     * and another panel can only be dropped after it, never in front of it.
     * Declared with [Satellite].
     */
    public val isReorderable: Boolean = true,
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

    /** `true` while the satellite is open and declared, i.e. a [DockLayout] would show its panel. */
    internal val isShown: Boolean get() = isOpen && content != null

    /**
     * The side [SatelliteScope.dock] targets when none is given: the last
     * docked side — to begin with the declared one, else the right side when
     * [dockSides] allows it, else the first side it allows.
     */
    public var preferredDockSide: DockSide by
        mutableStateOf(
            (initialPlacement as? SatellitePlacement.Docked)?.side
                ?: DockSide.Right.takeIf { it in dockSides }
                ?: dockSides.firstOrNull()
                ?: DockSide.Right,
        )
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

    /**
     * The docked placement this satellite last held on each side it has left
     * — the declared one to begin with — so [SatelliteWorkspace.dock] can put
     * it back at the rank and the share it had there rather than at the end
     * of the stack.
     */
    internal val dockMemory: MutableMap<DockSide, SatellitePlacement.Docked> =
        (initialPlacement as? SatellitePlacement.Docked)?.let { mutableMapOf(it.side to it) } ?: mutableMapOf()

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
 * A docked satellite's own size — [SatellitePlacement.Docked.extent] and
 * [SatellitePlacement.Docked.weight] — rides in its placement, so the
 * per-panel geometry of a layered or split side is part of the picture too.
 *
 * @property satellites snapshots keyed by satellite id.
 * @property dockExtents width (left/right) or height (top/bottom) of each
 *   split dock side, shared by the panels on it.
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

    /**
     * Windows that have joined, in join order.
     *
     * A snapshot of the live list, so reading it in composition subscribes to
     * it and comparing it with `==` means what it says.
     */
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
    ): Dp = extents[side] ?: dockSeedExtent(entry, side)

    /**
     * The thickness [entry] brings with it when docked on [side]: its own
     * extent when it comes from a dock on the same axis, else the size of its
     * floating window along that axis. What [dock] gives the panel and, for a
     * side with no extent of its own yet, what it seeds the side with — so a
     * drop preview drawn at this width shows the width the drop produces.
     */
    internal fun dockSeedExtent(
        entry: SatelliteEntry,
        side: DockSide,
    ): Dp {
        val docked = entry.placement as? SatellitePlacement.Docked
        if (docked != null && docked.side.isVertical == side.isVertical) {
            return docked.extent ?: dockExtent(docked.side)
        }
        return entry.windowState.size
            .let { if (side.isVertical) it.width else it.height }
            .coerceAtLeast(MinDockExtent)
    }

    /**
     * The weight [entry] takes among the panels of a split side once docked on
     * [side]: the one it has where it is docked now, else the one it last held
     * on [side], else `1`. What [dock] gives the panel, and what the drop
     * preview divides the stack with.
     */
    internal fun dockSeedWeight(
        entry: SatelliteEntry,
        side: DockSide,
    ): Float =
        (entry.placement as? SatellitePlacement.Docked)?.weight
            ?: entry.dockMemory[side]?.weight
            ?: 1f

    /** Sets [dockExtent]; clamped to [MinDockExtent]. Driven by the [DockLayout] splitters. */
    public fun setDockExtent(
        side: DockSide,
        extent: Dp,
    ) {
        extents[side] = extent.coerceAtLeast(MinDockExtent)
    }

    /**
     * Sets the own thickness of the docked satellite [id]
     * ([SatellitePlacement.Docked.extent]), clamped to [MinDockExtent]. What
     * the splitter of a panel on a *layered* side drags; a no-op for a
     * satellite that is not docked.
     */
    public fun setDockedExtent(
        id: String,
        extent: Dp,
    ) {
        updateDocked(id) { it.copy(extent = extent.coerceAtLeast(MinDockExtent)) }
    }

    /**
     * Sets the share of a split side the docked satellite [id] takes
     * ([SatellitePlacement.Docked.weight]); values at or below zero are
     * clamped to a small positive share. What the divider between two panels
     * on a *split* side drags; a no-op for a satellite that is not docked.
     */
    public fun setDockedWeight(
        id: String,
        weight: Float,
    ) {
        updateDocked(id) { it.copy(weight = weight.coerceAtLeast(MIN_DOCK_WEIGHT)) }
    }

    private fun updateDocked(
        id: String,
        transform: (SatellitePlacement.Docked) -> SatellitePlacement.Docked,
    ) {
        val entry = entryMap[id] ?: return
        val docked = entry.placement as? SatellitePlacement.Docked ?: return
        entry.placement = transform(docked)
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
     * else the current [owner]'s.
     *
     * [order] is the position the panel takes among the panels docked on that
     * side of that layout, closed ones included, counted from the top (left
     * and right sides) or the left (top and bottom sides) on a split side and
     * from the edge inwards on a layered one; the panels from there on move
     * one rank down, and the ranks of the side are kept contiguous from `0`.
     * `null` puts the satellite back at the rank it last held on that side —
     * the one it was declared with, or the one it left by [undock] or by a
     * move to another side — and appends it when it has never sat there, so a
     * palette that is floated and docked again lands where it was rather
     * than at the end. A re-dock on the side it already occupies keeps its
     * rank.
     *
     * The satellite brings its thickness along ([dockSeedExtent]): its own
     * extent when it comes from a dock on the same axis, else the size of its
     * floating window. A side with no [dockExtent] of its own yet is seeded
     * with it, so the panel keeps the width it had wherever it lands. The
     * weight is kept across a move between docks and remembered with the rank.
     *
     * A side the satellite was not declared for ([SatelliteEntry.dockSides])
     * is refused: nothing changes. [order] is ignored for a pinned satellite
     * ([SatelliteEntry.isReorderable] `false`), which keeps its declared
     * rank, and is pushed past the pinned panels of the side for any other —
     * a drop can join them but never displace one.
     */
    public fun dock(
        id: String,
        side: DockSide,
        order: Int? = null,
        host: TaoWindow? = null,
    ) {
        val entry = entryMap[id] ?: return
        if (side !in entry.dockSides) return
        val current = entry.placement
        val extent = dockSeedExtent(entry, side)
        val weight = dockSeedWeight(entry, side)
        if (current is SatellitePlacement.Floating) entry.lastFloating = currentFloating(entry, current)
        leaveStack(entry)
        val remembered = entry.dockMemory[side]
        if (side !in extents) setDockExtent(side, extent)
        entry.dockHost =
            host?.takeIf { it in members }
                ?: entry.dockHost?.takeIf { it in members }
                ?: owner
        entry.placement = SatellitePlacement.Docked(side, order = 0, extent, weight)
        // A pinned panel takes the rank it was declared with, whatever the
        // caller asks: that rank is the whole point of pinning it.
        insertInStack(entry, order?.takeIf { entry.isReorderable } ?: remembered?.order)
        entry.preferredDockSide = side
    }

    /**
     * Docks the satellite [id] where a drag resolved to: [DockTarget.order]
     * counts the panels *shown* on the side — what the user aimed between —
     * and is turned into the rank among every panel docked there, closed ones
     * included, before [dock] applies it.
     */
    internal fun dropAt(
        id: String,
        target: DockTarget,
    ) {
        val entry = entryMap[id] ?: return
        val order =
            target.order?.let { slot ->
                val stack = stackOf(target.side, target.host, exclude = entry)
                val before = stack.filter { it.isShown }.getOrNull(slot)
                before?.let(stack::indexOf) ?: stack.size
            }
        dock(id, target.side, order, target.host)
    }

    /**
     * The target that drops the docked satellite [entry] back where it is in
     * [host]: its side, at its own slot among the panels shown there — `null`
     * order when it is alone, which is what a drop on an empty side resolves
     * to. `null` for a satellite not docked in [host].
     */
    internal fun ownTarget(
        entry: SatelliteEntry,
        host: TaoWindow,
    ): DockTarget? {
        val docked = entry.placement as? SatellitePlacement.Docked ?: return null
        if (entry.dockHost !== host) return null
        if (!entry.isReorderable) return DockTarget(host, docked.side)
        val shown = stackOf(docked.side, host, exclude = null).filter { it.isShown }
        return DockTarget(host, docked.side, shown.indexOf(entry).takeIf { shown.size > 1 && it >= 0 })
    }

    /**
     * Turns the docked satellite [id] back into a floating window: at
     * [placement] when given, else over the panel it just was when the host's
     * geometry is known, else at its last floating position. No-op for a
     * floating satellite, and for a fixed one
     * ([SatelliteEntry.isFloatable] `false`), which never leaves the dock.
     */
    public fun undock(
        id: String,
        placement: SatellitePlacement.Floating? = null,
    ) {
        val entry = entryMap[id] ?: return
        if (!entry.isFloatable) return
        val docked = entry.placement as? SatellitePlacement.Docked ?: return
        entry.preferredDockSide = docked.side
        val floating = placement ?: liftOffPlacement(entry) ?: entry.lastFloating
        leaveStack(entry)
        applyFloating(entry, floating)
    }

    /**
     * Bakes where [entry]'s floating window currently is into its placement, so
     * a satellite that goes away and comes back — [close] then [open], or the
     * [visible] sweep — reappears where the user left it instead of at the rule
     * it was declared with. A no-op for a docked satellite, whose placement is
     * the dock.
     *
     * Driven by [Satellite] as the floating window leaves composition, which is
     * the last moment the live offset is known.
     */
    internal fun recordFloatingPlacement(entry: SatelliteEntry) {
        val floating = entry.placement as? SatellitePlacement.Floating ?: return
        val current = currentFloating(entry, floating)
        entry.lastFloating = current
        entry.placement = current
        entry.windowState.size = current.size
        entry.windowState.positioner = current.positioner
        entry.windowState.anchorRect = current.anchorRect
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

    /**
     * How the satellite in flight is being carried, or `null` while none is.
     *
     * Read it to draw a drag the way it actually behaves:
     * [SatelliteDragKind.Window] moves a real window under the pointer, so
     * [dragGhost] is published and a torn-out panel is something the user sees
     * leaving; [SatelliteDragKind.Transfer] carries the satellite in the
     * platform's drag-and-drop session — the picture under the pointer is the
     * drag icon the compositor draws, no window follows, and [dragGhost] stays
     * `null`. [draggedSatellite] and [dockPreview] are published either way.
     */
    public val dragKind: SatelliteDragKind?
        get() =
            when {
                drags.active != null -> SatelliteDragKind.Window
                transferDrag != null -> SatelliteDragKind.Transfer
                else -> null
            }

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
    public fun dockTargetAt(screenPx: Offset): DockTarget? = zoneOf { it.dockHitTest(screenPx, DockZoneWidth) }

    /**
     * The dock zone the satellite being dragged would land in, decided from
     * **where the satellite is** rather than from where the pointer is: the
     * zone [draggedScreenRectPx] — the floating window's frame, or the ghost
     * of a panel being torn out — has entered, the nearest edge winning. That
     * is what the user sees moving, so a palette whose edge has reached the
     * left strip highlights it even though the pointer is still in the middle
     * of the palette.
     *
     * The rect has to overlap the layout at all; a window merely parked beside
     * one is no drop. When the rect covers several zones at once — a palette
     * larger than the layout — [pointerScreenPx] breaks the tie, so a drop
     * still goes where the user is aiming. Overlapping layouts are tried as
     * for the pointer overload: the [owner]'s first, then by focus recency,
     * stopping at the layout the pointer is over.
     */
    public fun dockTargetAt(
        draggedScreenRectPx: Rect,
        pointerScreenPx: Offset,
    ): DockTarget? = zoneOf { it.dockHitTest(draggedScreenRectPx, pointerScreenPx, DockZoneWidth) }

    /**
     * Whether dragging [entry] could change anything: it can float, it has
     * another side or another window's dock to go to, or it may take another
     * rank among the panels shown beside it. `false` makes
     * [Modifier.satelliteDragHandle] inert rather than leaving a gesture that
     * cannot end anywhere.
     */
    internal fun canBeDragged(entry: SatelliteEntry): Boolean {
        if (entry.isFloatable) return true
        val docked = entry.placement as? SatellitePlacement.Docked ?: return true
        if (entry.dockSides.any { it != docked.side }) return true
        if (members.size > 1) return true
        return entry.isReorderable && stackOf(docked.side, entry.dockHost, exclude = entry).any { it.isShown }
    }

    /** [dockTargetAt] resolved for the satellite [entry] — see [targetFor]. */
    internal fun dockTargetFor(
        entry: SatelliteEntry,
        draggedScreenRectPx: Rect,
        pointerScreenPx: Offset,
    ): DockTarget? = dockTargetAt(draggedScreenRectPx, pointerScreenPx)?.let { targetFor(entry, it) }

    /**
     * [target] as a target for [entry]: `null` on a side [entry] was not
     * declared for, and without a rank for a pinned one — [dock] would ignore
     * it, so a preview drawn from it would promise a move that does not happen.
     */
    internal fun targetFor(
        entry: SatelliteEntry,
        target: DockTarget,
    ): DockTarget? =
        when {
            target.side !in entry.dockSides -> null
            entry.isReorderable -> target
            else -> target.copy(order = null)
        }

    private inline fun zoneOf(hitTest: (HostGeometry) -> DockHit?): DockTarget? {
        val hit =
            dockHosts
                .ordered(group.membersByRecency)
                .asSequence()
                .filter { !it.minimized() }
                .firstNotNullOfOrNull(hitTest)
        return (hit as? DockHit.Zone)?.target
    }

    /**
     * Starts dragging the satellite [id] from [origin], with the pointer at
     * [pointerScreenPx] (physical screen pixels). Feed the session the pointer
     * as it moves and release it with [SatelliteDragSession.end]; it moves a
     * floating window along, publishes [dockPreview] / [dragGhost], and docks,
     * re-docks or undocks on release. `null` when [id] is unknown, the
     * origin's geometry is not available, or the origin window has no
     * client-side screen placement (native Wayland: no window position to
     * drag from, none to drop onto — [dock] and [undock] still work there).
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
        val from =
            when (origin) {
                is SatelliteDragOrigin.FloatingWindow -> origin.window
                is SatelliteDragOrigin.DockedPanel -> origin.host
            }
        if (!from.canPlaceOnScreen) {
            from.warnScreenPlacementUnsupported("SatelliteWorkspace.beginDrag")
            return null
        }
        // Whatever was dragging until now is over: two live sessions would
        // fight over the same published state.
        transferDrag?.cancel()
        val session = createDragSession(entry, origin, start) ?: return null
        drags.begin(session)
        draggedSatellite = entry
        return session
    }

    // ── Drag and drop without screen placement (native Wayland) ──────────

    /**
     * The drag riding the platform's DnD session, or `null`. Started from a
     * grip in a window without client-side screen placement; every
     * [DockLayout] is a drop target for it and records the outcome on it, and
     * the session acts on that record when it ends. Feedback is the same as
     * for a pointer drag: [draggedSatellite] and [dockPreview].
     */
    internal var transferDrag: SatelliteTransferDrag? by mutableStateOf(null)
        private set

    /**
     * Starts the DnD-carried counterpart of [beginDrag] for the satellite
     * [id] from [origin]; `null` when [id] is unknown. Supersedes whichever
     * drag was live.
     */
    internal fun beginTransferDrag(
        id: String,
        origin: SatelliteDragOrigin,
    ): SatelliteTransferDrag? {
        val entry = entryMap[id] ?: return null
        transferDrag?.cancel()
        releaseDrag(null)
        val session =
            SatelliteTransferDrag(
                this,
                entry,
                origin,
                transferGhostSizePx(entry, origin),
                transferGhostSource(entry, origin),
            )
        transferDrag = session
        draggedSatellite = entry
        return session
    }

    /** `true` while [session] is the transfer drag in flight. */
    internal fun isLiveTransfer(session: SatelliteTransferDrag): Boolean = transferDrag === session

    /** Ends [session] if it is the one in flight and clears the drag feedback. Idempotent. */
    internal fun endTransferDrag(session: SatelliteTransferDrag) {
        if (transferDrag !== session) return
        transferDrag = null
        releaseDrag(null)
    }

    /**
     * The drag icon's size: the header strip of the dragged satellite, as wide
     * as its window or panel. Sizes stay valid where positions do not, so the
     * frame is read even on native Wayland.
     */
    @Suppress("MagicNumber") // outer frame is [x, y, w, h]
    private fun transferGhostSizePx(
        entry: SatelliteEntry,
        origin: SatelliteDragOrigin,
    ): Size {
        val window =
            when (origin) {
                is SatelliteDragOrigin.FloatingWindow -> origin.window
                is SatelliteDragOrigin.DockedPanel -> origin.host
            }
        val scale = window.scaleFactor.takeIf { it > 0f } ?: 1f
        val width =
            when (origin) {
                is SatelliteDragOrigin.FloatingWindow -> origin.outerBoundsPx()?.get(2)?.toFloat()
                is SatelliteDragOrigin.DockedPanel -> entry.dockedBoundsInWindowPx?.width
            } ?: (entry.windowState.size.width.value * scale)
        return Size(width, DockPanelHeaderHeight.value * scale)
    }

    /**
     * What the drag icon pictures: the whole floating window, or the docked
     * panel's own rect in its host — header included, since that is what the
     * user grabbed — when the layout has published it.
     */
    private fun transferGhostSource(
        entry: SatelliteEntry,
        origin: SatelliteDragOrigin,
    ): TransferGhostSource =
        when (origin) {
            is SatelliteDragOrigin.FloatingWindow -> TransferGhostSource.WholeWindow
            is SatelliteDragOrigin.DockedPanel ->
                entry.dockedBoundsInWindowPx
                    ?.takeIf { !it.isEmpty }
                    ?.let { TransferGhostSource.Region(it.roundToIntRect()) }
                    ?: TransferGhostSource.None
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
        dockSides: Set<DockSide> = DockSide.entries.toSet(),
        floatable: Boolean = true,
        reorderable: Boolean = true,
    ): SatelliteEntry {
        entryMap[id]?.let {
            it.title = title
            return it
        }
        require((initialPlacement as? SatellitePlacement.Docked)?.side?.let { it in dockSides } != false) {
            "satellite '$id' is declared docked on ${(initialPlacement as SatellitePlacement.Docked).side}, " +
                "a side its dockSides $dockSides do not allow"
        }
        require(floatable || initialPlacement is SatellitePlacement.Docked) {
            "satellite '$id' cannot float and is not declared docked: it would have nowhere to live"
        }
        require(reorderable || initialPlacement is SatellitePlacement.Docked) {
            "satellite '$id' is pinned to a rank and is not declared docked: there is no rank to pin it to"
        }
        val entry =
            SatelliteEntry(id, title, initialPlacement, initiallyOpen, dockSides, floatable, reorderable)
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
        // A snapshot is a consistent picture of every side, so the ranks it
        // carries are applied as they are; only the memory is kept up to date.
        (entry.placement as? SatellitePlacement.Docked)?.let { entry.dockMemory[it.side] = it }
        when (val placement = saved.placement) {
            is SatellitePlacement.Floating -> {
                // A fixed panel has no floating placement to go back to: the
                // snapshot predates the declaration, and the dock stands.
                if (!entry.isFloatable) return
                applyFloating(entry, placement)
                // Already on screen: move it, since placement is otherwise one-shot.
                entry.windowState.reanchor()
            }
            is SatellitePlacement.Docked -> {
                // A snapshot written before the declaration changed may name a
                // side the satellite no longer docks on: its placement is left as it is.
                if (placement.side !in entry.dockSides) return
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

    /**
     * The panels docked on [side] of [host]'s layout — open or not, every one
     * of them holds a rank — in rank order, without [exclude].
     */
    private fun stackOf(
        side: DockSide,
        host: TaoWindow?,
        exclude: SatelliteEntry?,
    ): List<SatelliteEntry> =
        entryMap.values
            .filter {
                it !== exclude &&
                    it.dockHost === host &&
                    (it.placement as? SatellitePlacement.Docked)?.side == side
            }.sortedWith(compareBy({ (it.placement as SatellitePlacement.Docked).order }, { it.id }))

    /**
     * Takes [entry] out of the stack it is docked in, remembering the
     * placement it held there and closing the rank it leaves behind. A no-op
     * for a floating satellite.
     */
    private fun leaveStack(entry: SatelliteEntry) {
        val docked = entry.placement as? SatellitePlacement.Docked ?: return
        entry.dockMemory[docked.side] = docked
        renumber(stackOf(docked.side, entry.dockHost, exclude = entry))
    }

    /**
     * Puts the freshly docked [entry] at [index] of its side's stack — the
     * end when `null` or past it — and renumbers the stack from `0`.
     *
     * A reorderable [entry] cannot land in front of a pinned panel: the ranks
     * are contiguous, so inserting there would shift every pinned panel from
     * that rank on. The insertion is pushed past the last of them. A pinned
     * [entry] itself is placed at the rank it asks for, which is the one it
     * was declared with.
     */
    private fun insertInStack(
        entry: SatelliteEntry,
        index: Int?,
    ) {
        val docked = entry.placement as SatellitePlacement.Docked
        val stack = stackOf(docked.side, entry.dockHost, exclude = entry).toMutableList()
        val floor = if (entry.isReorderable) pinnedFloor(stack) else 0
        stack.add((index ?: stack.size).coerceIn(floor, stack.size), entry)
        renumber(stack)
    }

    /** The first rank of [stack] a reorderable panel may take: past every pinned panel. */
    internal fun pinnedFloor(stack: List<SatelliteEntry>): Int = stack.indexOfLast { !it.isReorderable } + 1

    private fun renumber(stack: List<SatelliteEntry>) {
        stack.forEachIndexed { rank, member ->
            val docked = member.placement as SatellitePlacement.Docked
            if (docked.order != rank) member.placement = docked.copy(order = rank)
        }
    }

    /** Constants shared with [DockLayout]. */
    public companion object {
        /** Extent a dock side gets before any satellite seeded it. */
        public val DefaultDockExtent: Dp = 280.dp

        /** Smallest extent a dock side can be dragged or set to. */
        public val MinDockExtent: Dp = 80.dp

        /** Depth of the drop zone inside each edge of a [DockLayout]. */
        public val DockZoneWidth: Dp = 64.dp

        /** Smallest share a split-side panel can be dragged down to; keeps its divider reachable. */
        private const val MIN_DOCK_WEIGHT = 0.05f

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

/**
 * How a satellite drag in flight is carried — see [SatelliteWorkspace.dragKind].
 */
public enum class SatelliteDragKind {
    /**
     * The satellite's own window, or a ghost window standing in for a docked
     * panel, follows the pointer. [SatelliteWorkspace.dragGhost] is published
     * for a panel being torn out.
     */
    Window,

    /**
     * The platform's drag-and-drop session carries it, because the window
     * cannot be placed by the app ([TaoWindow.canPlaceOnScreen] `false`). The
     * source is not told where the pointer is: the window under it resolves
     * the drop and the source acts on that record.
     */
    Transfer,
}

/**
 * A dock zone: the [side] of the [DockLayout] in [host], and the rank
 * ([SatellitePlacement.Docked.order]) the dropped panel takes among the
 * panels shown on that side — `null` leaves the choice to
 * [SatelliteWorkspace.dock]: the rank the satellite last held there, else the
 * end. A drag resolves the rank from where the pointer is over the side's
 * stack, so a panel can be dropped between two others.
 */
public data class DockTarget(
    val host: TaoWindow,
    val side: DockSide,
    val order: Int? = null,
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
    val side = dockSideAt(rect, screenPx, zoneWidth.value * scaleFactor())
    return if (side != null) DockHit.Zone(DockTarget(host, side)) else DockHit.Content
}

/**
 * Where the dragged satellite [draggedRectPx] falls on this [DockLayout]
 * geometry, with the pointer at [pointerPx]: [DockHit.Zone] for the zone it
 * has entered, [DockHit.Content] when it is over the layout but clear of every
 * zone, `null` when neither it nor the pointer is on this layout at all.
 */
internal fun HostGeometry.dockHitTest(
    draggedRectPx: Rect,
    pointerPx: Offset,
    zoneWidth: Dp,
): DockHit? {
    val rect = layoutScreenRectPx() ?: return null
    val overlaps = !rect.intersect(draggedRectPx).isEmpty
    val onPointer = rect.contains(pointerPx)
    if (!overlaps && !onPointer) return null
    val zones = zoneScreenRectsPx(zoneWidth.value * scaleFactor()) ?: return null
    // Over the layout, in a zone or not: no other layout under it is
    // consulted, exactly as for a pointer hit.
    val side = dockSideEntered(zones, draggedRectPx, pointerPx) ?: return DockHit.Content
    return DockHit.Zone(DockTarget(host, side, zones.getValue(side).slotAt(pointerPx)))
}

/**
 * The zone of [zones] the dragged satellite has brought its edge to, or —
 * failing that — the zone [pointer] is in.
 *
 * [zones] are the rectangles the target actually draws, so the region that
 * lights up is the region a drag is measured against: on a layered side that
 * is the strip inset behind the layers already docked there, not the window's
 * own edge, which sits behind them.
 *
 * "Brought its edge to" is the satellite's own edge within one zone thickness
 * of the zone's outer edge, and the satellite overlapping the zone across the
 * other axis. The edge rather than any overlap is what keeps a tear-out
 * possible: a panel as tall as the layout overlaps the top and bottom strips
 * wherever it is dragged, and treating that as "entered" would pin it to a
 * zone for the whole gesture.
 *
 * The pointer over a side's stack — its [DockDropZone.slots] — is a zone
 * entered too: that is how a panel is dropped between two others. Several
 * zones at once — a palette larger than the layout reaches all four, a strip
 * runs across the corner of a neighbouring stack — are resolved by the
 * pointer: the one stack it is over, else the one strip it is in, so an
 * ambiguous overlap still drops where the user aims; else the closest edge
 * wins.
 */
internal fun dockSideEntered(
    zones: Map<DockSide, DockDropZone>,
    dragged: Rect,
    pointer: Offset,
): DockSide? {
    val live = zones.filterValues { !it.strip.isEmpty }
    val gaps =
        live
            .filter { (side, zone) -> overlapsAcross(zone.strip, dragged, side) }
            .mapValues { (side, zone) -> abs(edgePx(dragged, side) - outerEdgePx(zone.strip, side)) }
            .filter { (side, gap) -> gap <= thicknessPx(live.getValue(side).strip, side) }
    val overStack = live.filterValues { zone -> zone.slots.any { it.contains(pointer) } }.keys
    val underPointer = live.filterValues { it.contains(pointer) }.keys
    val candidates = gaps.keys + underPointer
    candidates.singleOrNull()?.let { return it }
    if (candidates.isEmpty()) return null
    overStack.singleOrNull()?.let { return it }
    underPointer.singleOrNull()?.let { return it }
    return candidates.minBy { gaps[it] ?: Float.MAX_VALUE }
}

/** Whether [dragged] overlaps [zone] along the axis the zone runs on. */
private fun overlapsAcross(
    zone: Rect,
    dragged: Rect,
    side: DockSide,
): Boolean =
    if (side.isVertical) {
        dragged.top < zone.bottom && zone.top < dragged.bottom
    } else {
        dragged.left < zone.right && zone.left < dragged.right
    }

/** The zone's outer boundary: the one against the layout's [side] edge. */
private fun outerEdgePx(
    zone: Rect,
    side: DockSide,
): Float =
    when (side) {
        DockSide.Left -> zone.left
        DockSide.Right -> zone.right
        DockSide.Top -> zone.top
        DockSide.Bottom -> zone.bottom
    }

/** The zone's own thickness: how far a satellite's edge may sit from it and still count. */
private fun thicknessPx(
    zone: Rect,
    side: DockSide,
): Float = if (side.isVertical) zone.width else zone.height

/** A strip of [widthPx] inside [rect]'s [side] edge: the zone a plain layout offers. */
internal fun edgeStripPx(
    rect: Rect,
    side: DockSide,
    widthPx: Float,
): Rect =
    when (side) {
        DockSide.Left -> Rect(rect.left, rect.top, rect.left + widthPx, rect.bottom)
        DockSide.Right -> Rect(rect.right - widthPx, rect.top, rect.right, rect.bottom)
        DockSide.Top -> Rect(rect.left, rect.top, rect.right, rect.top + widthPx)
        DockSide.Bottom -> Rect(rect.left, rect.bottom - widthPx, rect.right, rect.bottom)
    }

/** The edge of [rect] that faces [side]'s zone. */
private fun edgePx(
    rect: Rect,
    side: DockSide,
): Float =
    when (side) {
        DockSide.Left -> rect.left
        DockSide.Right -> rect.right
        DockSide.Top -> rect.top
        DockSide.Bottom -> rect.bottom
    }

/**
 * The dock zone of [rect] that [point] falls in: the nearest edge when the
 * point is within [zonePx] of it, else `null` (over the content, or outside
 * the rect altogether). Coordinate-space agnostic: screen pixels for a pointer
 * drag, window pixels for a drop the window itself reports.
 */
internal fun dockSideAt(
    rect: Rect,
    point: Offset,
    zonePx: Float,
): DockSide? {
    if (!rect.contains(point)) return null
    val (side, distance) =
        listOf(
            DockSide.Left to point.x - rect.left,
            DockSide.Right to rect.right - point.x,
            DockSide.Top to point.y - rect.top,
            DockSide.Bottom to rect.bottom - point.y,
        ).minBy { it.second }
    return side.takeIf { distance <= zonePx }
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
