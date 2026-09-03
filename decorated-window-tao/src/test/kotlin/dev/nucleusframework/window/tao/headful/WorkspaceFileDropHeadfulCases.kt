package dev.nucleusframework.window.tao.headful

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp

/**
 * Files dragged in from outside the application, on real windows.
 *
 * The OS delivers an inbound drag through the platform bridge callbacks, which
 * hand the window's scene root to `TaoSceneDnD`; these cases enter the same
 * funnel from inside the process (see [fileDropRecorder] and the helpers next
 * to it), so everything above the JNI boundary is the real thing: the synthetic
 * AWT transferable, Compose's drag-and-drop node tree, the application's own
 * `dragAndDropTarget`, and the paths read back through `awtTransferable`.
 *
 *  1. **the happy path** — enter, move, drop, and the paths arrive intact;
 *  2. **where a drop lands** — outside every target, between two targets, and
 *     past a target that refuses the drag;
 *  3. **drags that end badly** — one that leaves without dropping, an empty
 *     payload, paths that do not exist, hundreds of samples, drops back to back;
 *  4. **against the workspaces** — a drop into the tab a window is showing,
 *     a drop while a tab drag is live, and a drop aimed at a window that is
 *     being torn down under it.
 */
internal object WorkspaceFileDropHeadfulCases {
    fun all(): List<TaoWindowTestCase> =
        listOf(
            filesDroppedOnAWindowReachTheTarget(),
            aDropOutsideEveryTargetIsRefused(),
            aDragThatLeavesWithoutDroppingLeavesNoState(),
            twoTargetsAndOnlyTheOneUnderThePointerTakesIt(),
            aTargetThatRefusesTheDragLetsTheOneBelowHaveIt(),
            anEmptyPayloadStillReachesTheTarget(),
            pathsThatDoNotExistArriveVerbatim(),
            hundredsOfSamplesInOneFileDragStayConsistent(),
            dropsBackToBackEachDeliverTheirOwnFiles(),
            filesDroppedOnATabWindowLandInTheSelectedTab(),
            filesFollowTheSelectionAndTheTabToItsNewWindow(),
            aFileDragWhileATabDragIsLiveDisturbsNeither(),
            aDropAimedAtAClosingWindowIsSurvivable(),
            aDropOnEveryWindowOfASpreadReachesEachOne(),
        )

    // ── 1. the happy path ────────────────────────────────────────────────

    /**
     * One drag from the file manager, start to finish. What has to hold is not
     * that a callback fired but that the *paths* came out of the transferable
     * on the other side, in order — that is the whole contract an application
     * writes against.
     */
    private fun filesDroppedOnAWindowReachTheTarget(): TaoWindowTestCase {
        val log = FileDropLog()
        val files = dropFiles(count = 3)
        return TaoWindowTestCase(
            name = "file drop delivers every path to the target under the pointer",
            size = DpSize(DROP_WINDOW_W_DP.dp, DROP_WINDOW_H_DP.dp),
            paintDefaultBackground = false,
            content = {
                Box(Modifier.fillMaxSize().background(Color.DarkGray).fileDropRecorder(log))
            },
            driver = {
                awaitDropTarget()
                val point = contentPointPx(window, HALF, HALF)

                check(window.fileDragEnter(point)) { "the scene refused a file drag over a target" }
                awaitUntil("the target was entered") { log.entered.value == 1 }
                check(window.fileDragOver(point)) { "no eligible drop target while hovering one" }
                check(window.fileDrop(point, files)) { "the drop was refused" }

                awaitUntil("the drop was recorded") { log.drops.value == 1 }
                check(log.failure.value == null) { "reading the drop failed: ${log.failure.value}" }
                check(log.files.value == files) { "arrived as ${log.files.value}, dropped $files" }
                check(log.ended.value >= 1) { "the target was never told the drag ended" }
                settle()
                check(bounds() != null) { "the window did not survive a file drop" }
            },
        )
    }

    // ── 2. where a drop lands ────────────────────────────────────────────

    /**
     * A drop on the window but clear of every target: the scene has to say no,
     * so the OS can tell the user the drag was not taken rather than swallow
     * the files.
     */
    private fun aDropOutsideEveryTargetIsRefused(): TaoWindowTestCase {
        val log = FileDropLog()
        return TaoWindowTestCase(
            name = "file drop clear of every target is refused",
            size = DpSize(DROP_WINDOW_W_DP.dp, DROP_WINDOW_H_DP.dp),
            paintDefaultBackground = false,
            content = {
                Column(Modifier.fillMaxSize().background(Color.DarkGray)) {
                    Box(Modifier.fillMaxWidth().weight(1f).fileDropRecorder(log))
                    Box(Modifier.fillMaxWidth().weight(1f).background(Color(0xFF303030)))
                }
            },
            driver = {
                awaitDropTarget()
                val onTarget = contentPointPx(window, HALF, TOP_QUARTER)
                val offTarget = contentPointPx(window, HALF, BOTTOM_QUARTER)

                check(window.fileDragEnter(onTarget)) { "the top half must take the drag" }
                awaitUntil("entered on the target") { log.entered.value == 1 }
                check(!window.fileDragOver(offTarget)) { "the bottom half offered a drop target" }
                check(!window.fileDrop(offTarget, dropFiles(1))) { "a drop clear of every target was accepted" }
                settle()
                check(log.drops.value == 0) { "the target took a drop aimed elsewhere" }
            },
        )
    }

    /**
     * The user changes their mind: the drag leaves the window without a drop.
     * The target has to be told, and the window has to be ready for the next
     * one — a stuck "drag in progress" is what makes the second drop silently
     * do nothing.
     */
    private fun aDragThatLeavesWithoutDroppingLeavesNoState(): TaoWindowTestCase {
        val log = FileDropLog()
        val files = dropFiles(count = 1, prefix = "nucleus-after-leave")
        return TaoWindowTestCase(
            name = "file drag that leaves without dropping leaves the window ready for the next",
            size = DpSize(DROP_WINDOW_W_DP.dp, DROP_WINDOW_H_DP.dp),
            paintDefaultBackground = false,
            content = {
                Box(Modifier.fillMaxSize().background(Color.DarkGray).fileDropRecorder(log))
            },
            driver = {
                awaitDropTarget()
                val point = contentPointPx(window, HALF, HALF)

                window.fileDragEnter(point)
                window.fileDragOver(point)
                window.fileDragLeave()
                awaitUntil("the target was told the drag left") { log.exited.value >= 1 }
                settle()
                check(log.drops.value == 0) { "a drag that left dropped anyway" }

                // And the very next drag still works, all the way through.
                check(window.fileDragEnter(point)) { "the second drag was refused" }
                check(window.fileDrop(point, files)) { "the second drop was refused" }
                awaitUntil("the second drop arrived") { log.drops.value == 1 }
                check(log.files.value == files) { "the second drop arrived as ${log.files.value}" }
            },
        )
    }

    /**
     * Two targets side by side: the drop belongs to the one under the pointer
     * and to no other. This is the shape of a real window — a document area
     * and a palette, each taking its own files.
     */
    private fun twoTargetsAndOnlyTheOneUnderThePointerTakesIt(): TaoWindowTestCase {
        val top = FileDropLog()
        val bottom = FileDropLog()
        val toTop = dropFiles(count = 1, prefix = "nucleus-top")
        val toBottom = dropFiles(count = 2, prefix = "nucleus-bottom")
        return TaoWindowTestCase(
            name = "file drop with two targets reaches only the one under the pointer",
            size = DpSize(DROP_WINDOW_W_DP.dp, DROP_WINDOW_H_DP.dp),
            paintDefaultBackground = false,
            content = {
                Column(Modifier.fillMaxSize().background(Color.DarkGray)) {
                    Box(Modifier.fillMaxWidth().weight(1f).fileDropRecorder(top))
                    Box(Modifier.fillMaxWidth().weight(1f).fileDropRecorder(bottom))
                }
            },
            driver = {
                awaitDropTarget()
                val onTop = contentPointPx(window, HALF, TOP_QUARTER)
                val onBottom = contentPointPx(window, HALF, BOTTOM_QUARTER)

                check(window.fileDragAndDrop(onTop, toTop)) { "the top target refused its drop" }
                awaitUntil("the top target got it") { top.drops.value == 1 }
                check(bottom.drops.value == 0) { "the bottom target took the top's drop" }
                check(top.files.value == toTop) { "the top target got ${top.files.value}" }

                check(window.fileDragAndDrop(onBottom, toBottom)) { "the bottom target refused its drop" }
                awaitUntil("the bottom target got it") { bottom.drops.value == 1 }
                check(top.drops.value == 1) { "the top target took a second drop" }
                check(bottom.files.value == toBottom) { "the bottom target got ${bottom.files.value}" }
            },
        )
    }

    /**
     * A target that refuses the drag altogether — an area that takes text but
     * not files, say. The drop has to fall through to whatever is behind it
     * rather than be eaten by the refusal.
     */
    private fun aTargetThatRefusesTheDragLetsTheOneBelowHaveIt(): TaoWindowTestCase {
        val refusing = FileDropLog()
        val accepting = FileDropLog()
        val files = dropFiles(count = 2, prefix = "nucleus-fallthrough")
        return TaoWindowTestCase(
            name = "file drop falls through a target that refuses the drag",
            size = DpSize(DROP_WINDOW_W_DP.dp, DROP_WINDOW_H_DP.dp),
            paintDefaultBackground = false,
            content = {
                Box(Modifier.fillMaxSize().background(Color.DarkGray).fileDropRecorder(accepting)) {
                    Box(Modifier.fillMaxSize().fileDropRecorder(refusing, accept = false))
                }
            },
            driver = {
                awaitDropTarget()
                val point = contentPointPx(window, HALF, HALF)

                check(window.fileDragAndDrop(point, files)) { "no target took the drop" }
                awaitUntil("the accepting target got it") { accepting.drops.value == 1 }
                check(refusing.drops.value == 0) { "the refusing target took the drop" }
                check(refusing.entered.value == 0) { "the refusing target was entered" }
                check(accepting.files.value == files) { "arrived as ${accepting.files.value}" }
            },
        )
    }

    // ── 3. drags that end badly ──────────────────────────────────────────

    /**
     * A drop the OS reports with nothing in it — a drag of a kind we do not
     * carry, or a source that withdrew its data. The target still runs; it
     * just gets an empty list, and reading it must not throw.
     */
    private fun anEmptyPayloadStillReachesTheTarget(): TaoWindowTestCase {
        val log = FileDropLog()
        return TaoWindowTestCase(
            name = "file drop with an empty payload reaches the target without throwing",
            size = DpSize(DROP_WINDOW_W_DP.dp, DROP_WINDOW_H_DP.dp),
            paintDefaultBackground = false,
            content = {
                Box(Modifier.fillMaxSize().background(Color.DarkGray).fileDropRecorder(log))
            },
            driver = {
                awaitDropTarget()
                val point = contentPointPx(window, HALF, HALF)
                check(window.fileDragAndDrop(point, emptyList())) { "an empty drop was refused" }
                awaitUntil("the empty drop arrived") { log.drops.value == 1 }
                check(log.failure.value == null) { "reading an empty payload threw: ${log.failure.value}" }
                check(log.files.value.isEmpty()) { "an empty drop produced ${log.files.value}" }

                // And a real one right after it still works.
                val files = dropFiles(count = 1, prefix = "nucleus-after-empty")
                check(window.fileDragAndDrop(point, files))
                awaitUntil("the real drop arrived") { log.drops.value == 2 }
                check(log.files.value == files)
            },
        )
    }

    /**
     * Paths the drag names that are not on this machine — a stale drag from a
     * removed volume, a path only the source can see. Nothing in the chain may
     * touch the filesystem, so they have to arrive exactly as sent and let the
     * application decide.
     */
    private fun pathsThatDoNotExistArriveVerbatim(): TaoWindowTestCase {
        val log = FileDropLog()
        val ghosts =
            listOf(
                "/nucleus/does/not/exist/one.txt",
                "/nucleus/does/not/exist/two with spaces.txt",
                "/nucleus/does/not/exist/three-é-ü.txt",
            )
        return TaoWindowTestCase(
            name = "file drop of paths that do not exist arrives verbatim",
            size = DpSize(DROP_WINDOW_W_DP.dp, DROP_WINDOW_H_DP.dp),
            paintDefaultBackground = false,
            content = {
                Box(Modifier.fillMaxSize().background(Color.DarkGray).fileDropRecorder(log))
            },
            driver = {
                awaitDropTarget()
                val point = contentPointPx(window, HALF, HALF)
                check(window.fileDragAndDrop(point, ghosts)) { "the drop was refused" }
                awaitUntil("the drop arrived") { log.drops.value == 1 }
                check(log.failure.value == null) { "reading unreachable paths threw: ${log.failure.value}" }
                check(log.files.value == ghosts) { "arrived as ${log.files.value}" }
            },
        )
    }

    /**
     * A slow drag across the window: hundreds of move samples before the drop.
     * Enter has to happen once and only once, and the target must still be the
     * one that gets the files at the end.
     */
    private fun hundredsOfSamplesInOneFileDragStayConsistent(): TaoWindowTestCase {
        val log = FileDropLog()
        val files = dropFiles(count = 1, prefix = "nucleus-storm")
        return TaoWindowTestCase(
            name = "file drag with hundreds of samples enters once and drops once",
            timeoutMillis = LONG_CASE_TIMEOUT_MILLIS,
            size = DpSize(DROP_WINDOW_W_DP.dp, DROP_WINDOW_H_DP.dp),
            paintDefaultBackground = false,
            content = {
                Box(Modifier.fillMaxSize().background(Color.DarkGray).fileDropRecorder(log))
            },
            driver = {
                awaitDropTarget()
                val outer = requireNotNull(bounds())
                val start = contentPointPx(window, EDGE_INSET, HALF)
                check(window.fileDragEnter(start)) { "the drag was refused" }
                awaitUntil("entered once") { log.entered.value == 1 }

                repeat(SAMPLE_STORM) { step ->
                    val t = step / SAMPLE_STORM.toFloat()
                    val x = outer[RECT_W] * (EDGE_INSET + t * (1f - 2 * EDGE_INSET))
                    check(window.fileDragOver(Offset(x, outer[RECT_H] * HALF))) {
                        "sample $step found no drop target inside a full-window one"
                    }
                }
                settle()
                check(log.entered.value == 1) {
                    "the storm entered the target ${log.entered.value}× for one drag"
                }
                check(log.drops.value == 0) { "a move sample dropped" }

                val end = contentPointPx(window, 1f - EDGE_INSET, HALF)
                check(window.fileDrop(end, files)) { "the drop after the storm was refused" }
                awaitUntil("the storm ended in a drop") { log.drops.value == 1 }
                check(log.files.value == files) { "arrived as ${log.files.value}" }
            },
        )
    }

    /**
     * Drop after drop with no frame in between — the shape of a script feeding
     * a window, and of a user who drops a batch impatiently. Each drop carries
     * its own payload and none may leak into the next.
     */
    private fun dropsBackToBackEachDeliverTheirOwnFiles(): TaoWindowTestCase {
        val log = FileDropLog()
        return TaoWindowTestCase(
            name = "file drops back to back each deliver their own payload",
            timeoutMillis = LONG_CASE_TIMEOUT_MILLIS,
            size = DpSize(DROP_WINDOW_W_DP.dp, DROP_WINDOW_H_DP.dp),
            paintDefaultBackground = false,
            content = {
                Box(Modifier.fillMaxSize().background(Color.DarkGray).fileDropRecorder(log))
            },
            driver = {
                awaitDropTarget()
                val point = contentPointPx(window, HALF, HALF)
                val batches = (1..DROP_BURST).map { listOf("/nucleus/burst/$it.txt") }
                for ((index, batch) in batches.withIndex()) {
                    check(window.fileDragAndDrop(point, batch)) { "drop $index was refused" }
                }
                awaitUntil("every drop arrived") { log.drops.value == DROP_BURST }
                settle()
                check(log.failure.value == null) { "a drop in the burst threw: ${log.failure.value}" }
                check(log.allFiles.toList() == batches.flatten()) {
                    "the burst arrived as ${log.allFiles.toList()}"
                }
                check(bounds() != null) { "the window did not survive the burst" }
            },
        )
    }

    // ── 4. against the workspaces ────────────────────────────────────────

    /**
     * The everyday case in a tabbed application: files dropped on the window
     * belong to the document it is showing, and to no other tab.
     */
    private fun filesDroppedOnATabWindowLandInTheSelectedTab(): TaoWindowTestCase {
        val fixture = TabWorkspaceFixture(initialTitles = listOf("Alpha", "Beta"), fileDropTargets = true)
        val toAlpha = dropFiles(count = 1, prefix = "nucleus-alpha")
        val toBeta = dropFiles(count = 2, prefix = "nucleus-beta")
        return TaoWindowTestCase(
            name = "file drop on a tab window lands in the tab it is showing",
            skip = ::workspaceSkipReason,
            windowState = idleCaseWindowState(),
            size = idleCaseWindowSize(),
            paintDefaultBackground = false,
            applicationContent = { with(fixture) { Windows() } },
            driver = {
                val tabWindow = awaitTabWindows(fixture, "Alpha", "Beta")
                val workspace = fixture.workspace
                workspace.select(fixture.tabId("Alpha"))
                awaitUntil("Alpha is composed") { fixture.windowOf("Alpha") === tabWindow }
                settle(SETTLE_AFTER_MAP_MILLIS)

                val point = contentPointPx(tabWindow, HALF, BOTTOM_QUARTER)
                check(tabWindow.fileDragAndDrop(point, toAlpha)) { "the drop on Alpha was refused" }
                awaitUntil("Alpha took the files") { fixture.dropLog("Alpha").drops.value == 1 }
                check(fixture.dropLog("Alpha").files.value == toAlpha)
                check(fixture.dropLog("Beta").drops.value == 0) { "the hidden tab took the drop" }

                workspace.select(fixture.tabId("Beta"))
                awaitUntil("Beta is composed") { fixture.windowOf("Beta") === tabWindow }
                settle(SETTLE_AFTER_MAP_MILLIS)
                check(tabWindow.fileDragAndDrop(point, toBeta)) { "the drop on Beta was refused" }
                awaitUntil("Beta took the files") { fixture.dropLog("Beta").drops.value == 1 }
                check(fixture.dropLog("Beta").files.value == toBeta)
                check(fixture.dropLog("Alpha").drops.value == 1) { "Alpha took a second drop while hidden" }
            },
        )
    }

    /**
     * A tab torn into a window of its own keeps its drop target: the body
     * moved, so the files dropped on the *new* window have to reach it there,
     * and the window it left must not answer for it any more.
     */
    private fun filesFollowTheSelectionAndTheTabToItsNewWindow(): TaoWindowTestCase {
        val fixture = TabWorkspaceFixture(initialTitles = listOf("Alpha", "Beta"), fileDropTargets = true)
        val files = dropFiles(count = 1, prefix = "nucleus-torn")
        return TaoWindowTestCase(
            name = "file drop follows a tab into the window it was torn into",
            skip = ::workspaceSkipReason,
            windowState = idleCaseWindowState(),
            size = idleCaseWindowSize(),
            paintDefaultBackground = false,
            applicationContent = { with(fixture) { Windows() } },
            driver = {
                val first = awaitTabWindows(fixture, "Alpha", "Beta")
                val workspace = fixture.workspace
                val beta = fixture.tabId("Beta")

                val torn = requireNotNull(workspace.tearOff(beta, tearOffRectPx(first), first.scaleFactor))
                val tornWindow = awaitMappedStrip(fixture, torn)
                awaitUntil("Beta composes in its own window") { fixture.windowOf("Beta") === tornWindow }
                settle(SETTLE_AFTER_MAP_MILLIS)

                val onTorn = contentPointPx(tornWindow, HALF, BOTTOM_QUARTER)
                check(tornWindow.fileDragAndDrop(onTorn, files)) { "the torn-off window refused the drop" }
                awaitUntil("Beta took the files in its new window") { fixture.dropLog("Beta").drops.value == 1 }
                check(fixture.dropLog("Beta").files.value == files)
                check(fixture.dropLog("Alpha").drops.value == 0) { "the window Beta left took the drop" }

                // The window it came from is still a target of its own.
                val onFirst = contentPointPx(first, HALF, BOTTOM_QUARTER)
                val other = dropFiles(count = 1, prefix = "nucleus-home")
                check(first.fileDragAndDrop(onFirst, other)) { "the source window stopped taking drops" }
                awaitUntil("Alpha took its own files") { fixture.dropLog("Alpha").drops.value == 1 }
                check(fixture.dropLog("Alpha").files.value == other)
            },
        )
    }

    /**
     * Two drag mechanisms live at once: the user is holding a tab with the
     * mouse while a file drag from another application crosses the window.
     * They share nothing, so neither may disturb the other — and the tab drag
     * has to be exactly where it was when the files land.
     */
    private fun aFileDragWhileATabDragIsLiveDisturbsNeither(): TaoWindowTestCase {
        val fixture =
            TabWorkspaceFixture(initialTitles = listOf("Alpha", "Beta", "Gamma"), fileDropTargets = true)
        val files = dropFiles(count = 1, prefix = "nucleus-during-drag")
        return TaoWindowTestCase(
            name = "file drag crossing a live tab drag disturbs neither",
            skip = ::workspaceSkipReason,
            windowState = idleCaseWindowState(),
            size = idleCaseWindowSize(),
            paintDefaultBackground = false,
            applicationContent = { with(fixture) { Windows() } },
            driver = {
                val first = awaitTabWindows(fixture, "Alpha", "Beta", "Gamma")
                val workspace = fixture.workspace
                val beta = fixture.tabId("Beta")
                val group = requireNotNull(fixture.groupOf("Beta"))
                val grab = requireNotNull(fixture.tabCenterPx("Beta"))
                val away = requireNotNull(fixture.farFromStripPx(group))

                val session = requireNotNull(workspace.beginDrag(beta, stripOrigin(first), grab))
                session.update(grab)
                session.update(away)
                check(workspace.dragGhost != null) { "the tab tear-out must be previewed" }

                val selected = requireNotNull(workspace.selectedTab(group)).title
                val point = contentPointPx(first, HALF, BOTTOM_QUARTER)
                check(first.fileDragAndDrop(point, files)) { "the file drop was refused mid tab drag" }
                awaitUntil("the files reached the selected tab") { fixture.dropLog(selected).drops.value == 1 }

                check(workspace.draggedTab?.id == beta) { "the file drag ended the tab drag" }
                check(workspace.dragGhost != null) { "the file drag cleared the tab ghost" }
                check(workspace.groups.size == 1) { "the file drag moved a tab" }

                session.end(away)
                awaitUntil("the tab drag still lands") {
                    workspace.groups.size == 2 && fixture.groupOf("Beta")?.ids == listOf(beta)
                }
                check(fixture.dropLog(selected).files.value == files) { "the files were lost by the tab drag" }
            },
        )
    }

    /**
     * A drop aimed at a window the application is closing in the same frame —
     * the drag was accepted by a scene that no longer exists by the time the
     * files arrive. Nothing may throw, and the surviving window has to keep
     * taking drops.
     */
    private fun aDropAimedAtAClosingWindowIsSurvivable(): TaoWindowTestCase {
        val fixture = TabWorkspaceFixture(initialTitles = listOf("Alpha", "Beta"), fileDropTargets = true)
        val files = dropFiles(count = 1, prefix = "nucleus-closing")
        return TaoWindowTestCase(
            name = "file drop aimed at a window closing under it is survivable",
            skip = ::workspaceSkipReason,
            windowState = idleCaseWindowState(),
            size = idleCaseWindowSize(),
            paintDefaultBackground = false,
            applicationContent = { with(fixture) { Windows() } },
            driver = {
                val first = awaitTabWindows(fixture, "Alpha", "Beta")
                val workspace = fixture.workspace
                val beta = fixture.tabId("Beta")
                val torn = requireNotNull(workspace.tearOff(beta, tearOffRectPx(first), first.scaleFactor))
                val tornWindow = awaitMappedStrip(fixture, torn)
                awaitUntil("Beta composes in its own window") { fixture.windowOf("Beta") === tornWindow }
                settle(SETTLE_AFTER_MAP_MILLIS)

                val point = contentPointPx(tornWindow, HALF, BOTTOM_QUARTER)
                check(tornWindow.fileDragEnter(point)) { "the torn-off window refused the drag" }

                var destroyed = false
                tornWindow.onDestroyed { destroyed = true }
                workspace.close(beta)
                awaitUntil("the window went away under the drag") { destroyed }
                settle()

                // The OS has no way of knowing; it delivers the drop anyway.
                check(!tornWindow.fileDrop(point, files)) { "a destroyed window accepted a drop" }
                tornWindow.fileDragLeave()
                settle(SETTLE_AFTER_MAP_MILLIS)
                check(fixture.dropLog("Beta").drops.value == 0) { "a closed tab took a drop" }

                // The survivor is untouched.
                val onFirst = contentPointPx(first, HALF, BOTTOM_QUARTER)
                check(first.fileDragAndDrop(onFirst, files)) { "the surviving window stopped taking drops" }
                awaitUntil("the surviving window took the files") { fixture.dropLog("Alpha").drops.value == 1 }
            },
        )
    }

    /**
     * Files dropped on each of several windows in turn. Every window owns its
     * own scene and its own drop target; a single shared one would send every
     * drop to whichever window happened to be focused.
     */
    private fun aDropOnEveryWindowOfASpreadReachesEachOne(): TaoWindowTestCase {
        val titles = listOf("Alpha", "Beta", "Gamma")
        val fixture = TabWorkspaceFixture(initialTitles = titles, fileDropTargets = true)
        return TaoWindowTestCase(
            name = "file drops on a spread of windows each reach their own tab",
            timeoutMillis = LONG_CASE_TIMEOUT_MILLIS,
            skip = ::workspaceSkipReason,
            windowState = idleCaseWindowState(),
            size = idleCaseWindowSize(),
            paintDefaultBackground = false,
            applicationContent = { with(fixture) { Windows() } },
            driver = {
                val first = awaitTabWindows(fixture, *titles.toTypedArray())
                val workspace = fixture.workspace
                for (title in titles.drop(1)) {
                    val from = requireNotNull(fixture.groupOf(title)?.window)
                    val group =
                        requireNotNull(
                            workspace.tearOff(fixture.tabId(title), tearOffRectPx(from), from.scaleFactor),
                        )
                    awaitMappedStrip(fixture, group)
                }
                awaitUntil("every tab composes in a window of its own") {
                    titles.mapNotNull { fixture.windowOf(it) }.distinct().size == titles.size
                }
                settle(SETTLE_AFTER_MAP_MILLIS)

                val payloads = titles.associateWith { listOf("/nucleus/spread/${it.lowercase()}.txt") }
                for (title in titles) {
                    val host = requireNotNull(fixture.windowOf(title)) { "$title has no window" }
                    val point = contentPointPx(host, HALF, BOTTOM_QUARTER)
                    check(host.fileDragAndDrop(point, requireNotNull(payloads[title]))) {
                        "$title's window refused its drop"
                    }
                }
                awaitUntil("every window took exactly one drop") {
                    titles.all { fixture.dropLog(it).drops.value == 1 }
                }
                settle()
                for (title in titles) {
                    check(fixture.dropLog(title).files.value == payloads[title]) {
                        "$title got ${fixture.dropLog(title).files.value}"
                    }
                }
            },
        )
    }

    /** Waits until this case's window has attached a scene that can answer a drag. */
    private suspend fun TaoWindowTestScope.awaitDropTarget() {
        awaitUntil("window mapped") { bounds() != null }
        awaitUntil("the scene published a drop target") { window.hasSceneDropTarget() }
        settle(SETTLE_AFTER_MAP_MILLIS)
    }

    private const val DROP_WINDOW_W_DP = 480
    private const val DROP_WINDOW_H_DP = 320
    private const val HALF = 0.5f
    private const val TOP_QUARTER = 0.25f
    private const val BOTTOM_QUARTER = 0.75f
    private const val EDGE_INSET = 0.1f
    private const val SAMPLE_STORM = 300
    private const val DROP_BURST = 20
    private const val LONG_CASE_TIMEOUT_MILLIS = 90_000L
}
