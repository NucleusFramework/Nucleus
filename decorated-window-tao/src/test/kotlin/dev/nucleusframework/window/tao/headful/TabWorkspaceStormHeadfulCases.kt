package dev.nucleusframework.window.tao.headful

import androidx.compose.ui.geometry.Offset
import dev.nucleusframework.window.tao.TabWindowGroup
import kotlin.math.abs

/**
 * The tab workspace under storms, on real windows: operations fired faster than
 * the loop can settle, and windows the geometry alone cannot tell apart.
 *
 *  1. **selection storms** — hundreds of selection changes, after which exactly
 *     one body per window may be composing and every tab must still own its own
 *     saveable state;
 *  2. **reorder storms** — the strip republishes a slot per tab on every
 *     layout, so what has to hold at the end is that the slots describe the
 *     strip that is drawn;
 *  3. **sample storms** — hundreds of pointer samples inside one window drag,
 *     which is what a slow drag across a large screen really delivers;
 *  4. **stacked windows** — several windows at the same position, where only
 *     focus recency decides which strip answers a drop;
 *  5. **a snapshot against churn** — a saved layout has to be a description,
 *     not a moment: it must put everything back after a burst of moves.
 *
 * Native Wayland is skipped along with the rest of the tab suite.
 */
internal object TabWorkspaceStormHeadfulCases {
    fun all(): List<TaoWindowTestCase> =
        listOf(
            aStormOfSelectionsLeaksNoBodies(),
            aStormOfReordersKeepsTheSlotsConsistent(),
            stackedWindowsResolveToTheFocusedStrip(),
            aSnapshotConvergesBackAfterChurn(),
            hundredsOfSamplesInOneWindowDrag(),
        )

    /**
     * Selection changed hundreds of times with no frame in between. Each
     * arriving body must get its own state and each leaving body must go, so
     * the count of composing bodies stays at one per window however fast the
     * selection moves — and every tab's saveable state has to survive its
     * turns off screen.
     */
    private fun aStormOfSelectionsLeaksNoBodies(): TaoWindowTestCase {
        val titles = listOf("Alpha", "Beta", "Gamma", "Delta")
        val fixture = TabWorkspaceFixture(initialTitles = titles)
        return TaoWindowTestCase(
            name = "tab storm of selections leaks no bodies and no state",
            timeoutMillis = LONG_CASE_TIMEOUT_MILLIS,
            skip = ::workspaceSkipReason,
            windowState = idleCaseWindowState(),
            size = idleCaseWindowSize(),
            paintDefaultBackground = false,
            applicationContent = { with(fixture) { Windows() } },
            driver = {
                awaitTabWindows(fixture, *titles.toTypedArray())
                val workspace = fixture.workspace

                // Give each tab a distinct saveable value first.
                for ((index, title) in titles.withIndex()) {
                    workspace.select(fixture.tabId(title))
                    awaitUntil("$title composed") { fixture.counters.value[fixture.tabId(title)] != null }
                    requireNotNull(fixture.counters.value[fixture.tabId(title)]).value = index + 1
                }

                repeat(SELECTION_STORM) { round ->
                    workspace.select(fixture.tabId(titles[round % titles.size]))
                }
                awaitUntil("the storm settled on the last selection") {
                    fixture.windowOf(titles[(SELECTION_STORM - 1) % titles.size]) != null
                }
                settle(SETTLE_AFTER_MAP_MILLIS)
                check(fixture.composedBodies.value == 1) {
                    "the storm left ${fixture.composedBodies.value} bodies composing"
                }
                // Every tab keeps its own value: a body must never be handed
                // the saveable registry of the one it replaced.
                for ((index, title) in titles.withIndex()) {
                    workspace.select(fixture.tabId(title))
                    awaitUntil("$title is back") { fixture.windowOf(title) != null }
                    settle(SELECTION_SETTLE_MILLIS)
                    val counter = requireNotNull(fixture.counters.value[fixture.tabId(title)])
                    check(counter.value == index + 1) {
                        "$title came back with ${counter.value}, expected ${index + 1}"
                    }
                }
                check(workspace.groups.size == 1) { "the storm opened a window" }
            },
        )
    }

    /**
     * Reordered as fast as the workspace will take it. The strip republishes a
     * slot per tab on every layout, so what this pins down is that the slot
     * list never ends up shorter than the strip or stale enough to resolve a
     * drop to a tab that is somewhere else.
     */
    private fun aStormOfReordersKeepsTheSlotsConsistent(): TaoWindowTestCase {
        val titles = listOf("Alpha", "Beta", "Gamma", "Delta")
        val fixture = TabWorkspaceFixture(initialTitles = titles)
        return TaoWindowTestCase(
            name = "tab storm of reorders keeps the strip slots consistent",
            timeoutMillis = LONG_CASE_TIMEOUT_MILLIS,
            skip = ::workspaceSkipReason,
            windowState = idleCaseWindowState(),
            size = idleCaseWindowSize(),
            paintDefaultBackground = false,
            applicationContent = { with(fixture) { Windows() } },
            driver = {
                awaitTabWindows(fixture, *titles.toTypedArray())
                val workspace = fixture.workspace
                val group = requireNotNull(fixture.groupOf("Alpha"))

                repeat(REORDER_STORM) { round ->
                    val title = titles[round % titles.size]
                    workspace.reorder(fixture.tabId(title), round % titles.size)
                }
                // A slot per tab is not enough: the storm reordered them, so the
                // published slots have to have caught up with the strip order —
                // left to right, no crossings. That is what makes an insertion
                // index mean anything, and the wait the assertions below need.
                awaitUntil("the strip republished its slots in strip order") {
                    val slots = group.slotsInWindowPx
                    slots.size == group.ids.size &&
                        slots.zipWithNext().all { (left, right) -> left.left < right.left }
                }
                settle(SETTLE_AFTER_MAP_MILLIS)
                check(group.ids.toSet() == titles.map(fixture::tabId).toSet()) {
                    "the storm lost or duplicated a tab: ${group.ids}"
                }
                check(group.ids.size == titles.size) { "the strip holds ${group.ids.size} tabs" }
                check(workspace.groups.size == 1) { "the storm opened a window" }

                // The published slots still describe the strip that is drawn:
                // each tab's own centre resolves to the index it occupies.
                for ((index, id) in group.ids.withIndex()) {
                    val title = titles.first { fixture.tabId(it) == id }
                    val centre = requireNotNull(fixture.tabCenterPx(title)) { "$title has no slot" }
                    val entry = requireNotNull(workspace.tab(id))
                    val resolved = requireNotNull(workspace.dropTargetAt(centre, exclude = entry))
                    check(resolved.group === group) { "$title's centre resolves to another window" }
                    check(resolved.index == index) {
                        "$title sits at $index but its centre resolves to ${resolved.index}"
                    }
                }
            },
        )
    }

    /**
     * Windows stacked exactly on top of each other: geometry alone cannot say
     * which strip a drop belongs to, so focus recency has to. This is the
     * everyday case of two document windows on the same spot, and the one
     * where a stale focus order silently drops tabs into the window behind.
     */
    private fun stackedWindowsResolveToTheFocusedStrip(): TaoWindowTestCase {
        val titles = listOf("Alpha", "Beta", "Gamma")
        val fixture = TabWorkspaceFixture(initialTitles = titles)
        return TaoWindowTestCase(
            name = "tab storm stacked windows resolve a drop to the focused strip",
            timeoutMillis = LONG_CASE_TIMEOUT_MILLIS,
            skip = ::workspaceSkipReason,
            windowState = idleCaseWindowState(),
            size = idleCaseWindowSize(),
            paintDefaultBackground = false,
            applicationContent = { with(fixture) { Windows() } },
            driver = {
                val first = awaitTabWindows(fixture, *titles.toTypedArray())
                val workspace = fixture.workspace

                // Beta and Gamma into windows of their own, all three stacked
                // at the same place.
                val groups = ArrayList<TabWindowGroup>()
                groups += requireNotNull(fixture.groupOf("Alpha"))
                for (title in listOf("Beta", "Gamma")) {
                    val group =
                        requireNotNull(
                            workspace.tearOff(fixture.tabId(title), tearOffRectPx(first), first.scaleFactor),
                        )
                    awaitMappedStrip(fixture, group)
                    groups += group
                }
                val anchor = requireNotNull(first.outerBoundsPx())
                val scale = first.scaleFactor.toDouble()
                for (group in groups.drop(1)) {
                    requireNotNull(group.window).setOuterPosition(anchor[0] / scale, anchor[1] / scale)
                }
                awaitUntil("every window is stacked on the first one") {
                    groups.all { group ->
                        val now = group.window?.outerBoundsPx() ?: return@all false
                        abs(now[0] - anchor[0]) <= STACK_TOLERANCE_PX && abs(now[1] - anchor[1]) <= STACK_TOLERANCE_PX
                    }
                }
                settle(SETTLE_AFTER_MAP_MILLIS)

                // Whichever window was focused last owns the point.
                for (group in groups) {
                    val window = requireNotNull(group.window)
                    window.focus()
                    awaitUntil("the ${group.id} window reports focus") { window.isFocused }
                    awaitUntil("and its strip owns the shared point") {
                        val strip = fixture.stripRectPx(group) ?: return@awaitUntil false
                        workspace.dropTargetAt(strip.center)?.group === group
                    }
                }

                // A drop on the shared point lands in the focused window, and
                // the dragged window's own strip never answers for itself.
                val front = groups.last()
                requireNotNull(front.window).focus()
                awaitUntil("the front window is focused") { requireNotNull(front.window).isFocused }
                val alpha = fixture.tabId("Alpha")
                val source = groups.first()
                val sourceWindow = requireNotNull(source.window)
                val grab = requireNotNull(fixture.tabCenterPx("Alpha"))
                val shared = requireNotNull(fixture.stripRectPx(front)).center
                val session = requireNotNull(workspace.beginDrag(alpha, stripOrigin(sourceWindow), grab))
                session.update(grab)
                session.update(shared)
                settle(JUMP_SETTLE_MILLIS)
                val preview = requireNotNull(workspace.dropPreview) { "the stack previewed no drop" }
                check(preview.group === front) { "the drop resolved to ${preview.group.id}, not the focused window" }
                session.end(shared)
                awaitUntil("the tab landed in the focused window") {
                    fixture.groupOf("Alpha") === front && front.ids.contains(alpha)
                }
                settle(SETTLE_AFTER_MAP_MILLIS)
                check(workspace.groups.size == 2) { "the merge left ${workspace.groups.size} windows" }
            },
        )
    }

    /**
     * A snapshot has to be a description of a layout, not of a moment: taken
     * before a burst of moves, reorders and tear-offs, applying it afterwards
     * must put every window and every strip back exactly as they were.
     */
    private fun aSnapshotConvergesBackAfterChurn(): TaoWindowTestCase {
        val titles = listOf("Alpha", "Beta", "Gamma", "Delta")
        val fixture = TabWorkspaceFixture(initialTitles = titles)
        return TaoWindowTestCase(
            name = "tab storm a snapshot converges back after a burst of churn",
            timeoutMillis = LONG_CASE_TIMEOUT_MILLIS,
            skip = ::workspaceSkipReason,
            windowState = idleCaseWindowState(),
            size = idleCaseWindowSize(),
            paintDefaultBackground = false,
            applicationContent = { with(fixture) { Windows() } },
            driver = {
                val first = awaitTabWindows(fixture, *titles.toTypedArray())
                val workspace = fixture.workspace

                // Two windows of two tabs each, which is the layout to get back.
                val second =
                    requireNotNull(
                        workspace.tearOff(fixture.tabId("Gamma"), tearOffRectPx(first), first.scaleFactor),
                    )
                awaitMappedStrip(fixture, second)
                workspace.move(fixture.tabId("Delta"), second)
                awaitUntil("two windows of two tabs") {
                    workspace.groups.size == 2 && workspace.groups.all { it.ids.size == 2 }
                }
                settle(SETTLE_AFTER_MAP_MILLIS)
                val snapshot = workspace.snapshot()
                val savedOf = snapshot.groups.associate { it.id to it.tabIds }
                val savedSelection = snapshot.groups.associate { it.id to it.selectedId }

                // A burst that ends somewhere else entirely.
                repeat(CHURN_ROUNDS) { round ->
                    val title = titles[round % titles.size]
                    val id = fixture.tabId(title)
                    val group = fixture.groupOf(title) ?: return@repeat
                    if (round % 3 == 0) {
                        val window = group.window ?: return@repeat
                        workspace.tearOff(id, tearOffRectPx(window), window.scaleFactor)
                    } else {
                        val other = workspace.groups.firstOrNull { it !== group } ?: return@repeat
                        workspace.move(id, other, index = round % 2)
                    }
                }
                awaitUntil("the churn settled") { workspace.tabs.all { it.group != null } }
                settle(SETTLE_AFTER_MAP_MILLIS)

                workspace.restore(snapshot)
                awaitUntil("the saved layout is back") {
                    workspace.groups.size == snapshot.groups.size &&
                        workspace.groups.all { savedOf[it.id] == it.ids }
                }
                awaitUntil("both restored windows are mapped") {
                    workspace.groups.all { it.window?.hasRealFramePx() == true }
                }
                settle(SETTLE_AFTER_MAP_MILLIS)
                for (group in workspace.groups) {
                    check(group.selectedId == savedSelection[group.id]) {
                        "group ${group.id} came back showing ${group.selectedId}, saved ${savedSelection[group.id]}"
                    }
                }
                awaitUntil("one body per window composes") {
                    fixture.composedBodies.value == workspace.groups.size
                }
            },
        )
    }

    /**
     * Hundreds of samples in one gesture, which is what a slow deliberate drag
     * across a 4K screen actually delivers. Each one moves a real window, so
     * this is also the throughput check: the loop has to stay responsive and
     * the window has to end up under the pointer, not somewhere behind it.
     */
    private fun hundredsOfSamplesInOneWindowDrag(): TaoWindowTestCase {
        val fixture = TabWorkspaceFixture(initialTitles = listOf("Alpha", "Beta"))
        return TaoWindowTestCase(
            name = "tab storm hundreds of samples in one window drag stay in step",
            timeoutMillis = LONG_CASE_TIMEOUT_MILLIS,
            skip = ::workspaceSkipReason,
            windowState = idleCaseWindowState(),
            size = idleCaseWindowSize(),
            paintDefaultBackground = false,
            applicationContent = { with(fixture) { Windows() } },
            driver = {
                val first = awaitTabWindows(fixture, "Alpha", "Beta")
                val workspace = fixture.workspace
                val beta = fixture.tabId("Beta")

                val second =
                    requireNotNull(workspace.tearOff(beta, tearOffRectPx(first), first.scaleFactor))
                val secondWindow = awaitMappedStrip(fixture, second)
                val start = requireNotNull(fixture.tabCenterPx("Beta"))
                val before = requireNotNull(secondWindow.outerBoundsPx())

                val session = requireNotNull(workspace.beginDrag(beta, stripOrigin(secondWindow), start))
                session.update(start)
                // A slow arc, one sample at a time, ending back where it began
                // so the window's own strip cannot drift off the pointer.
                repeat(SAMPLE_STORM) { step ->
                    val t = step / SAMPLE_STORM.toFloat()
                    val wobble = SAMPLE_ARC_PX * kotlin.math.sin(t * Math.PI * 2).toFloat()
                    session.update(start + Offset(wobble, wobble / 2f))
                }
                session.update(start)
                settle()
                awaitUntil("the window came back to where the pointer is") {
                    val now = secondWindow.outerBoundsPx() ?: return@awaitUntil false
                    abs(now[0] - before[0]) <= SAMPLE_END_TOLERANCE_PX &&
                        abs(now[1] - before[1]) <= SAMPLE_END_TOLERANCE_PX
                }
                session.end(start)
                settle(SETTLE_AFTER_MAP_MILLIS)

                check(workspace.groups.size == 2) { "the storm changed the window count" }
                check(fixture.groupOf("Beta") === second) { "the storm moved the tab" }
                check(workspace.draggedTab == null && workspace.dragGhost == null) { "drag feedback left behind" }
                // The loop is still alive: another gesture works right after.
                val grab = requireNotNull(fixture.tabCenterPx("Beta"))
                val home = requireNotNull(fixture.groupOf("Alpha"))
                val target = requireNotNull(fixture.stripPointPx(home, 0.02f))
                val merge = requireNotNull(workspace.beginDrag(beta, stripOrigin(secondWindow), grab))
                merge.update(grab)
                merge.update(target)
                merge.end(target)
                awaitUntil("the follow-up gesture merged the windows") {
                    workspace.groups.size == 1 && fixture.groupOf("Beta") === home
                }
            },
        )
    }

    private const val CHURN_ROUNDS = 6
    private const val SELECTION_STORM = 200
    private const val REORDER_STORM = 120
    private const val SAMPLE_STORM = 400
    private const val SAMPLE_ARC_PX = 120f
    private const val SAMPLE_END_TOLERANCE_PX = 24L
    private const val STACK_TOLERANCE_PX = 24L
    private const val SELECTION_SETTLE_MILLIS = 120L
    private const val LONG_CASE_TIMEOUT_MILLIS = 90_000L
}
