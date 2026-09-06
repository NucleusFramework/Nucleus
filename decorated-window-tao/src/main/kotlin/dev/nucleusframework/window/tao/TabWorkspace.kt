package dev.nucleusframework.window.tao

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import dev.nucleusframework.window.tao.workspace.DragController
import dev.nucleusframework.window.tao.workspace.HostGeometry
import dev.nucleusframework.window.tao.workspace.HostGeometryRegistry
import dev.nucleusframework.window.tao.workspace.RelocatableSlot
import dev.nucleusframework.window.tao.workspace.WindowGroup
import dev.nucleusframework.window.tao.workspace.sanitizedOrNull
import dev.nucleusframework.window.tao.workspace.supportsScreenPlacement
import dev.nucleusframework.window.tao.workspace.warnScreenPlacementUnsupported

/**
 * One tab known to a [TabWorkspace]: its identity, title and body.
 *
 * Created by [Tab] on first composition (or by [TabWorkspace.restore] ahead of
 * it) and kept for the lifetime of the workspace, so a tab the app takes out
 * of composition and brings back resumes where it was.
 */
public class TabEntry internal constructor(
    /** Stable identity, the key used by every [TabWorkspace] operation. */
    public val id: String,
    title: String,
) {
    /** Human-readable title, shown on the tab. */
    public var title: String by mutableStateOf(title)
        internal set

    /** The window group this tab currently belongs to. */
    public var group: TabWindowGroup? by mutableStateOf(null)
        internal set

    /** `true` while this tab is the selected one of its group. */
    public val isSelected: Boolean get() = group?.selectedId == id

    internal var content: (@Composable TabScope.() -> Unit)? by mutableStateOf(null)

    /** `rememberSaveable` values carried across a move between groups. */
    internal val stateSlot: RelocatableSlot = RelocatableSlot()
}

/**
 * One window's worth of tabs: the tabs it holds in strip order, which of them
 * is selected, and the geometry of the window showing them.
 *
 * A group exists exactly as long as it holds at least one tab — tearing the
 * last tab out of a window closes that window, and dropping a tab in empty
 * space opens a new one. [TabWindows] composes one [DecoratedWindow] per group.
 */
public class TabWindowGroup internal constructor(
    /** Stable identity, unique within the workspace and stable across a restore. */
    public val id: String,
    initialPosition: DpOffset?,
    initialSize: DpSize,
) {
    internal val tabIds = mutableStateListOf<String>()

    /** The id of the selected tab, or `null` while the group is empty. */
    public var selectedId: String? by mutableStateOf(null)
        internal set

    /** Where the group's window is; `null` lets the platform place it. */
    public var position: DpOffset? by mutableStateOf(initialPosition)
        internal set

    /** The size of the group's window. */
    public var size: DpSize by mutableStateOf(initialSize)
        internal set

    /** The group's native window, once [TabWindows] has mapped it. */
    public var window: TaoWindow? by mutableStateOf(null)
        internal set

    /**
     * The tab ids this group holds, in strip order.
     *
     * A snapshot of the live list, so reading it in composition subscribes to
     * it and comparing it with `==` means what it says — the observable list
     * Compose keeps underneath compares by identity.
     */
    public val ids: List<String> get() = tabIds.toList()

    /** Rect of each tab in [ids], in window coordinates (physical px), published by the strip. */
    internal var slotsInWindowPx: List<Rect> = emptyList()

    /**
     * Bumped every time [position] / [size] are set by the workspace rather
     * than by the user. [TabWindows] pushes the new placement onto its window
     * when it changes, and only then — a window the user is dragging must not
     * be snapped back by a recomposition.
     */
    internal var placementRevision: Int by mutableStateOf(0)
        private set

    internal fun requestPlacement(
        position: DpOffset?,
        size: DpSize,
    ) {
        this.position = position
        this.size = size
        placementRevision++
    }
}

/**
 * Per-group part of a [TabLayoutSnapshot].
 *
 * @property id the group's identity, restored as-is so a snapshot round trip
 *   keeps the same windows.
 * @property tabIds the tabs it held, in strip order.
 * @property selectedId which of them was selected.
 * @property position where its window was, `null` when the platform placed it.
 * @property size the size of its window.
 */
public data class TabGroupSnapshot(
    val id: String,
    val tabIds: List<String>,
    val selectedId: String?,
    val position: DpOffset?,
    val size: DpSize,
)

/**
 * Serializable-by-the-app picture of a [TabWorkspace]: every window, the tabs
 * it holds and where it sits. Produce it with [TabWorkspace.snapshot], apply it
 * with [TabWorkspace.restore].
 *
 * @property groups the groups, in the order their windows were created.
 */
public data class TabLayoutSnapshot(
    val groups: List<TabGroupSnapshot>,
)

/**
 * A set of tabs spread over however many windows the user has pulled them
 * into — the Chrome tab model.
 *
 * Tabs are **declared** once against the workspace ([Tab]) and the workspace
 * decides which window shows each of them; [TabWindows] composes one window
 * per non-empty [TabWindowGroup]:
 *
 *  - **Moving.** [move] puts a tab in another group at a given index, [reorder]
 *    moves it within its own, [tearOff] pulls it into a group of its own at a
 *    screen rect. A group that loses its last tab is dropped, and its window
 *    goes with it; a tear-off adds one, and a window appears.
 *  - **Dragging.** [Modifier.tabDragHandle] — installed on every tab of the
 *    default strip — drives it: dragging a tab out of a multi-tab window
 *    previews it under the pointer and lands it in whichever strip it is
 *    dropped on, or in a new window; dragging the *only* tab of a window moves
 *    that window instead, exactly as Chrome does, and merges it into the strip
 *    it is dropped on.
 *  - **Selection.** [select] picks the visible tab of a group; a tab arriving
 *    from a drag is selected in its new group, and a group whose selected tab
 *    leaves selects its neighbour.
 *
 * `rememberSaveable` state inside a tab's body survives every move; plain
 * `remember` state does not, exactly as when any composable moves between
 * windows — hoist it or make it saveable.
 *
 * Every member of this class is meant for the Tao event-loop thread, which is
 * also the Compose dispatcher.
 *
 * @param defaultWindowSize the size a group's window gets when nothing else
 *   determines it: the first group, and any group restored without a size.
 */
@Suppress("TooManyFunctions")
public class TabWorkspace(
    public val defaultWindowSize: DpSize = DefaultWindowSize,
) {
    private val windows = WindowGroup(followFocus = true)

    private val entryMap = mutableStateMapOf<String, TabEntry>()
    private val groupList = mutableStateListOf<TabWindowGroup>()
    private var nextGroupId = 0
    private val pendingRestore = ArrayList<TabGroupSnapshot>()

    /** Every tab declared so far, in declaration order. */
    public val tabs: Collection<TabEntry> get() = entryMap.values

    /** The tab registered under [id], if any. */
    public fun tab(id: String): TabEntry? = entryMap[id]

    /** The groups holding tabs, in the order their windows were created. */
    public val groups: List<TabWindowGroup> get() = groupList

    /** The group with [id], if any. */
    public fun group(id: String): TabWindowGroup? = groupList.firstOrNull { it.id == id }

    /** The group whose window is [window], if any. */
    public fun groupOf(window: TaoWindow?): TabWindowGroup? =
        window?.let { groupList.firstOrNull { group -> group.window === it } }

    /**
     * The group whose window was focused most recently, or the first one; the
     * window a new tab opens in when none is named. `null` while empty.
     */
    public val activeGroup: TabWindowGroup?
        get() = groupOf(windows.owner) ?: groupList.firstOrNull()

    /** The tabs of [group], in strip order. */
    public fun tabsOf(group: TabWindowGroup): List<TabEntry> = group.tabIds.mapNotNull(entryMap::get)

    /** The selected tab of [group], or `null` while it holds none. */
    public fun selectedTab(group: TabWindowGroup): TabEntry? = group.selectedId?.let(entryMap::get)

    // ── Windows ──────────────────────────────────────────────────────────

    /** Records the window of [group] and makes it a member for focus tracking. Driven by [TabWindows]. */
    internal fun attachWindow(
        group: TabWindowGroup,
        window: TaoWindow,
    ) {
        group.window = window
        windows.join(window)
    }

    /** Forgets the window of [group]. Driven by [TabWindows] when the window leaves composition. */
    internal fun detachWindow(group: TabWindowGroup) {
        group.window?.let(windows::leave)
        group.window = null
    }

    /** Records [window] as the most recently focused group window. */
    internal fun noteWindowFocus(window: TaoWindow) {
        windows.noteFocus(window)
    }

    // ── Tabs ─────────────────────────────────────────────────────────────

    /** Makes [tabId] the visible tab of its group; a no-op for an unknown tab. */
    public fun select(tabId: String) {
        val entry = entryMap[tabId] ?: return
        entry.group?.selectedId = tabId
    }

    /**
     * Removes the tab [tabId] from the workspace: its group selects a
     * neighbour, and a group left empty is dropped along with its window.
     *
     * The tab is forgotten entirely, state included — a closed tab is gone,
     * unlike a satellite, which is only hidden.
     */
    public fun close(tabId: String) {
        val entry = entryMap.remove(tabId) ?: return
        entry.group?.let { detach(it, tabId) }
        entry.group = null
    }

    /**
     * Moves [tabId] into [group] at [index] (clamped; `null` appends), and
     * selects it there. Within its own group this is a [reorder]. A group left
     * empty by the move is dropped.
     */
    public fun move(
        tabId: String,
        group: TabWindowGroup,
        index: Int? = null,
    ) {
        val entry = entryMap[tabId] ?: return
        if (group !in groupList) return
        val from = entry.group
        if (from === group) {
            reorder(tabId, index ?: group.tabIds.lastIndex)
            return
        }
        from?.let { detach(it, tabId) }
        val at = (index ?: group.tabIds.size).coerceIn(0, group.tabIds.size)
        group.tabIds.add(at, tabId)
        entry.group = group
        group.selectedId = tabId
    }

    /** Moves [tabId] to [index] within its own group (clamped). */
    public fun reorder(
        tabId: String,
        index: Int,
    ) {
        val group = entryMap[tabId]?.group ?: return
        val current = group.tabIds.indexOf(tabId)
        if (current < 0) return
        val at = index.coerceIn(0, group.tabIds.lastIndex)
        if (at == current) return
        group.tabIds.removeAt(current)
        group.tabIds.add(at, tabId)
    }

    /**
     * Pulls [tabId] into a group of its own whose window covers
     * [screenRectPx] (physical screen pixels, outer frame), and returns that
     * group — or the tab's existing group when it is already alone in one,
     * which is then moved rather than duplicated.
     *
     * [scaleFactor] is the px-per-dp the rect was measured at. Windows are
     * placed in logical pixels, so on a mixed-DPI desktop a rect measured on
     * one display and applied on another is off by the ratio of their scales;
     * the drop lands where the pointer is either way.
     *
     * A drag sizes the rect from the window the tab came from, except when
     * that window fills the screen — a tab pulled out of a maximized window
     * gets [defaultWindowSize] rather than a second screen-sized window.
     */
    public fun tearOff(
        tabId: String,
        screenRectPx: Rect,
        scaleFactor: Float,
    ): TabWindowGroup? {
        val entry = entryMap[tabId] ?: return null
        val scale = scaleFactor.takeIf { it > 0f } ?: 1f
        val position = DpOffset((screenRectPx.left / scale).dp, (screenRectPx.top / scale).dp)
        val size = contentSizeDp(screenRectPx, scale, entry.group)
        entry.group?.takeIf { it.tabIds.size == 1 }?.let { alone ->
            // Already a window of its own: this is a move, not a tear-off.
            // Requested rather than merely recorded, so a caller driving the
            // gesture itself really moves the window — a drag never reaches
            // here, since the only tab of a window is dragged by moving that
            // window ([TabDragOrigin.Strip] takes the window-drag path).
            alone.requestPlacement(position, size)
            return alone
        }
        val group = TabWindowGroup(nextGroupId(), position, size)
        groupList += group
        move(tabId, group)
        return group
    }

    /**
     * The size to request for a window whose *frame* should cover [rectPx].
     *
     * A window is sized in content pixels while a tear-off rect is an outer
     * frame, so a window created straight from the rect is one chrome too big.
     * On Win32 that is the invisible resize border, and it compounds: a tab
     * dragged out, merged back and dragged out again gains it every round.
     *
     * [source] is the group the rect was measured on; the difference between
     * its own frame and the content its strip published is the best estimate
     * of the chrome the new window will get. Without one — nothing composed
     * yet, no screen placement — the rect is taken as-is, which is what this
     * always did.
     */
    @Suppress("MagicNumber") // outer frame is [x, y, w, h]
    private fun contentSizeDp(
        rectPx: Rect,
        scale: Float,
        source: TabWindowGroup?,
    ): DpSize {
        val geometry = source?.let { stripHosts[it.window] }
        val content = geometry?.containerSizePx?.takeIf { it.width > 0 && it.height > 0 }
        val outer = source?.window?.outerBoundsPx()
        if (content == null || outer == null) {
            return DpSize((rectPx.width / scale).dp, (rectPx.height / scale).dp)
        }
        val chromeW = (outer[2] - content.width).coerceAtLeast(0L)
        val chromeH = (outer[3] - content.height).coerceAtLeast(0L)
        return DpSize(
            ((rectPx.width - chromeW) / scale).dp,
            ((rectPx.height - chromeH) / scale).dp,
        )
    }

    /** Removes [tabId] from [group], reselecting and dropping the group as needed. */
    private fun detach(
        group: TabWindowGroup,
        tabId: String,
    ) {
        val index = group.tabIds.indexOf(tabId)
        if (index < 0) return
        group.tabIds.removeAt(index)
        if (group.selectedId == tabId) {
            // The neighbour to the right, else to the left — what a browser does.
            group.selectedId = group.tabIds.getOrNull(index) ?: group.tabIds.lastOrNull()
        }
        if (group.tabIds.isEmpty()) {
            detachWindow(group)
            groupList -= group
        }
    }

    private fun nextGroupId(): String = "group-${nextGroupId++}"

    // ── Drag and drop ────────────────────────────────────────────────────

    /** The strip geometry every group's window publishes, for hit-testing drops. */
    internal val stripHosts: HostGeometryRegistry = HostGeometryRegistry()

    /** The published strip geometry of [group], or `null` before its first layout. */
    internal fun stripGeometry(group: TabWindowGroup): HostGeometry? = stripHosts[group.window]

    private val drags =
        DragController<TabDragSession> {
            draggedTab = null
            dropPreview = null
            dragGhost = null
        }

    /**
     * The tab being dragged right now, or `null`. While it is set every strip
     * in the workspace shows where the tab can be dropped.
     */
    public var draggedTab: TabEntry? by mutableStateOf(null)
        internal set

    /**
     * Where the tab being dragged would land if released now, or `null` when
     * releasing would tear it into a window of its own. Strips highlight the
     * insertion point.
     */
    public var dropPreview: TabDropTarget? by mutableStateOf(null)
        internal set

    /**
     * The preview of a tab being dragged out of its strip, or `null`.
     * [TabWindows] shows it as a borderless window that follows the pointer,
     * so pulling a tab out of a window is something you can see leaving it.
     *
     * `null` for a single-tab window: there the window itself follows the
     * pointer, and a ghost on top of it would be a second copy of the tab.
     */
    public var dragGhost: TabDragGhost? by mutableStateOf(null)
        internal set

    /** The drag currently owning the feedback state, or `null`. */
    internal val activeDragSession: TabDragSession? get() = drags.active

    /** `true` while [session] is the one the workspace is publishing. */
    internal fun isLiveDrag(session: TabDragSession): Boolean = drags.isLive(session)

    /** Ends [session] if it is live (`null`: whichever is) and clears the drag feedback. Idempotent. */
    internal fun releaseDrag(session: TabDragSession?) {
        drags.release(session)
    }

    /**
     * Where [screenPx] (physical screen pixels) would insert a tab: the group
     * whose strip is under it and the index it would take, or `null` when no
     * strip is. Where windows overlap, the focused group is tried first, then
     * the others by focus recency; a minimized window is never a target.
     *
     * [exclude] is left out of the search — the tab being dragged, so hovering
     * its own position is not an insertion.
     *
     * [excludeGroup] is skipped entirely, and the search carries on to the
     * strip below it. That is what a single-tab window being dragged needs:
     * its own strip travels with the pointer and covers whatever it is being
     * dropped on, and it is also the focused window, so it would otherwise
     * answer every query and no merge could ever resolve.
     */
    public fun dropTargetAt(
        screenPx: Offset,
        exclude: TabEntry? = null,
        excludeGroup: TabWindowGroup? = null,
    ): TabDropTarget? =
        stripHosts
            .ordered(windows.membersByRecency)
            .asSequence()
            .filterNot { it.minimized() }
            .mapNotNull { geometry ->
                val strip = geometry.layoutScreenRectPx() ?: return@mapNotNull null
                if (!strip.contains(screenPx)) return@mapNotNull null
                val group = groupOf(geometry.host)?.takeIf { it !== excludeGroup } ?: return@mapNotNull null
                val client = geometry.clientOriginPx() ?: return@mapNotNull null
                TabDropTarget(group, insertionIndex(group, screenPx.x - client.x, exclude))
            }.firstOrNull()

    /**
     * The index [xInWindowPx] falls at in [group]'s strip: the number of tabs
     * whose midpoint is left of it, counting the dragged tab's own slot out so
     * the index it would land at is the one it already has.
     */
    internal fun insertionIndex(
        group: TabWindowGroup,
        xInWindowPx: Float,
        exclude: TabEntry?,
    ): Int =
        group.slotsInWindowPx
            .zip(group.tabIds)
            .filterNot { (_, id) -> id == exclude?.id }
            .takeWhile { (slot, _) -> xInWindowPx >= slot.center.x }
            .size

    /**
     * Starts dragging the tab [tabId] from [origin], with the pointer at
     * [pointerScreenPx] (physical screen pixels). Feed the session the pointer
     * as it moves and release it with [TabDragSession.end]; it publishes
     * [dropPreview] / [dragGhost] and moves, reorders or tears the tab off on
     * release. `null` when [tabId] is unknown, the origin's geometry is not
     * available, or the origin window has no client-side screen placement
     * (native Wayland: no window position to drag from, no strip to drop
     * onto — [move] and the snapshot API still work there).
     *
     * [Modifier.tabDragHandle] drives this from a pointer gesture; call it
     * directly to drive the same moves from another input source.
     */
    public fun beginDrag(
        tabId: String,
        origin: TabDragOrigin,
        pointerScreenPx: Offset,
    ): TabDragSession? {
        val entry = entryMap[tabId] ?: return null
        val start = pointerScreenPx.sanitizedOrNull() ?: return null
        val from =
            when (origin) {
                is TabDragOrigin.Strip -> origin.window
            }
        if (!from.supportsScreenPlacement) {
            from.warnScreenPlacementUnsupported("TabWorkspace.beginDrag")
            return null
        }
        transferDrag?.cancel()
        val session = createTabDragSession(entry, origin, start) ?: return null
        drags.begin(session)
        draggedTab = entry
        return session
    }

    // ── Drag and drop without screen placement (native Wayland) ──────────

    /**
     * The drag riding the platform's DnD session, or `null`. Started from a
     * tab in a window without client-side screen placement; every strip is a
     * drop target for it and records the insertion on it, and the session
     * acts on that record when it ends. Feedback is the same as for a pointer
     * drag: [draggedTab] and [dropPreview].
     */
    internal var transferDrag: TabTransferDrag? by mutableStateOf(null)
        private set

    /**
     * Starts the DnD-carried counterpart of [beginDrag] for [tabId], dragged
     * from its strip in [window]; `null` when the tab or its group is unknown.
     * Supersedes whichever drag was live.
     */
    internal fun beginTransferDrag(
        tabId: String,
        window: TaoWindow,
    ): TabTransferDrag? {
        val entry = entryMap[tabId] ?: return null
        val group = groupOf(window) ?: return null
        transferDrag?.cancel()
        releaseDrag(null)
        val session = createTabTransferDrag(entry, group, window)
        transferDrag = session
        draggedTab = entry
        return session
    }

    /** `true` while [session] is the transfer drag in flight. */
    internal fun isLiveTransfer(session: TabTransferDrag): Boolean = transferDrag === session

    /** Ends [session] if it is the one in flight and clears the drag feedback. Idempotent. */
    internal fun endTransferDrag(session: TabTransferDrag) {
        if (transferDrag !== session) return
        transferDrag = null
        releaseDrag(null)
    }

    // ── Layout persistence ───────────────────────────────────────────────

    /** Captures every group, the tabs it holds and where its window sits. */
    public fun snapshot(): TabLayoutSnapshot =
        TabLayoutSnapshot(
            groups =
                groupList.map { group ->
                    TabGroupSnapshot(
                        id = group.id,
                        tabIds = group.tabIds.toList(),
                        selectedId = group.selectedId,
                        position = liveWindowPosition(group) ?: group.position,
                        size = liveWindowSize(group) ?: group.size,
                    )
                },
        )

    /**
     * Applies [snapshot]: tabs it names are moved into the groups it
     * describes, and groups whose tabs are all still to be declared are
     * applied as those tabs appear. Tabs the snapshot does not name keep
     * whichever group they are in — or, if that group is dropped, follow it to
     * the first restored one.
     *
     * A snapshot applies once. A tab it named that is closed and declared
     * again afterwards is a new tab, and opens in the active window like any
     * other; call [restore] again to put the saved layout back.
     */
    public fun restore(snapshot: TabLayoutSnapshot) {
        pendingRestore.clear()
        for (saved in snapshot.groups) {
            val known = saved.tabIds.filter(entryMap::containsKey)
            if (known.isEmpty()) {
                pendingRestore += saved
                continue
            }
            val group = group(saved.id) ?: TabWindowGroup(saved.id, saved.position, saved.size).also { groupList += it }
            group.requestPlacement(saved.position, saved.size)
            for (id in known) move(id, group)
            // After the moves: a tab arriving selects itself, and the snapshot
            // has the last word on which one shows.
            group.selectedId = saved.selectedId?.takeIf { it in group.tabIds } ?: group.tabIds.lastOrNull()
            val undeclared = saved.tabIds - known.toSet()
            if (undeclared.isNotEmpty()) pendingRestore += saved.copy(tabIds = undeclared)
        }
    }

    @Suppress("MagicNumber") // outer frame is [x, y, w, h]
    private fun liveWindowPosition(group: TabWindowGroup): DpOffset? {
        val window = group.window ?: return null
        val outer = window.outerBoundsPx() ?: return null
        val scale = window.scaleFactor.takeIf { it > 0f } ?: return null
        return DpOffset((outer[0] / scale).dp, (outer[1] / scale).dp)
    }

    @Suppress("MagicNumber") // outer frame is [x, y, w, h]
    private fun liveWindowSize(group: TabWindowGroup): DpSize? {
        val window = group.window ?: return null
        val outer = window.outerBoundsPx() ?: return null
        val scale = window.scaleFactor.takeIf { it > 0f } ?: return null
        return DpSize((outer[2] / scale).dp, (outer[3] / scale).dp)
    }

    // ── Registration (driven by the Tab composable) ───────────────────────

    internal fun register(
        id: String,
        title: String,
        groupId: String?,
    ): TabEntry {
        entryMap[id]?.let {
            it.title = title
            return it
        }
        val entry = TabEntry(id, title)
        entryMap[id] = entry
        placeOnFirstDeclaration(entry, groupId)
        return entry
    }

    /**
     * Puts a freshly declared tab where it belongs: the group a pending
     * restore names, else the one the app asked for, else the active window,
     * else a new one.
     */
    private fun placeOnFirstDeclaration(
        entry: TabEntry,
        groupId: String?,
    ) {
        val restored = pendingRestore.firstOrNull { entry.id in it.tabIds }
        if (restored != null) {
            val group =
                group(restored.id)
                    ?: TabWindowGroup(restored.id, restored.position, restored.size).also { groupList += it }
            // At the index the snapshot had it, as far as the tabs declared so
            // far allow: restoring in declaration order must not shuffle them.
            val index = restored.tabIds.filter { it in group.tabIds || it == entry.id }.indexOf(entry.id)
            move(entry.id, group, index)
            // `move` selects what arrives, which is right for a drag and wrong
            // here: the snapshot decides, as soon as the tab it names is in.
            restored.selectedId?.takeIf { it in group.tabIds }?.let { group.selectedId = it }
            return
        }
        val target =
            groupId?.let { id -> group(id) ?: TabWindowGroup(id, null, defaultWindowSize).also { groupList += it } }
                ?: activeGroup
                ?: TabWindowGroup(nextGroupId(), null, defaultWindowSize).also { groupList += it }
        move(entry.id, target)
    }

    internal fun unregister(entry: TabEntry) {
        entry.content = null
    }

    /** Defaults shared with [TabWindows] and [TabStrip]. */
    public companion object {
        /** Size a group's window gets when nothing else determines it. */
        public val DefaultWindowSize: DpSize = DpSize(960.dp, 640.dp)
    }
}

/** Where a tab drag would insert the tab: at [index] in [group]'s strip. */
public data class TabDropTarget(
    val group: TabWindowGroup,
    val index: Int,
)

/**
 * The preview of a tab being dragged out of its strip: which tab, and where it
 * sits on screen right now (physical screen pixels, outer frame of the ghost
 * window), with the px-per-dp of the window it came from.
 */
public data class TabDragGhost(
    val tab: TabEntry,
    val screenRectPx: Rect,
    val scaleFactor: Float,
)

/** Where a tab drag starts; see [TabWorkspace.beginDrag]. */
public sealed interface TabDragOrigin {
    /**
     * The tab's own strip in [window]. Geometry is read through lambdas so
     * tests can stand in for the native window.
     */
    public class Strip internal constructor(
        public val window: TaoWindow,
        internal val outerBoundsPx: () -> LongArray?,
        internal val move: (xPx: Int, yPx: Int) -> Unit,
    ) : TabDragOrigin {
        public constructor(window: TaoWindow) : this(window, window::outerBoundsPx, window::setOuterPositionPx)
    }
}

/**
 * A tab drag in progress. Positions are physical screen pixels. Obtained from
 * [TabWorkspace.beginDrag].
 *
 * A session stops acting the moment it is no longer the workspace's current
 * drag — cancelled, finished, or superseded by another [TabWorkspace.beginDrag].
 * Every method is then a no-op, so a late release from an abandoned gesture
 * cannot move a window or a tab. All three are safe to call repeatedly and in
 * any order.
 *
 * Positions that are not finite (an `Offset.Unspecified` from a detached
 * layout, an infinity) are ignored rather than propagated into window
 * geometry; the last usable position stands.
 */
public interface TabDragSession {
    /** The pointer moved. */
    public fun update(pointerScreenPx: Offset)

    /** The pointer was released: move, reorder or tear off according to where. */
    public fun end(pointerScreenPx: Offset)

    /** The gesture was abandoned: nothing changes. */
    public fun cancel()
}

/** Remembers a [TabWorkspace] for the lifetime of the calling composition. */
@Composable
public fun rememberTabWorkspace(defaultWindowSize: DpSize = TabWorkspace.DefaultWindowSize): TabWorkspace =
    remember { TabWorkspace(defaultWindowSize) }
