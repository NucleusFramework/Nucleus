@file:Suppress("MagicNumber")

package dev.nucleusframework.window.tao

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalContext
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState
import dev.nucleusframework.core.runtime.Platform
import dev.nucleusframework.window.tao.ffi.NativeTaoWindowsDecoBridge
import dev.nucleusframework.window.tao.workspace.supportsScreenPlacement
import kotlinx.coroutines.delay

/**
 * A satellite window: an auxiliary top-level that belongs to another window.
 *
 * Satellites are the floating tool palettes, inspectors and mixer strips of a
 * desktop app — windows that are *about* a document window rather than
 * documents of their own. The archetype comes from Flutter's multi-window
 * design; this is the Tao implementation of the same contract:
 *
 *  - **Anchored** — the initial position comes from a [WindowPositioner]
 *    ([SatelliteWindowState.positioner]) resolved against the parent's frame
 *    or a sub-rectangle of it, and kept inside the monitor work area.
 *  - **Follows its parent** — once placed, the satellite holds its offset from
 *    the parent's top-left corner: drag the parent and the satellite comes
 *    along. Drag the *satellite* and the new offset is what gets preserved.
 *  - **Above, but not modal** — it stays in front of its parent in z-order,
 *    keeps out of the taskbar / Dock / Alt-Tab, follows it across workspaces
 *    and minimisation, and leaves it fully interactive.
 *  - **Steps aside** — while the parent is fullscreen or maximized the
 *    satellite hides itself rather than covering content
 *    ([hideWhileParentFullscreenOrMaximized]). With that turned off it stays
 *    over its parent instead: the owner link is re-asserted across the
 *    transition, which is what keeps the platform from re-stacking the
 *    satellite behind the window it belongs to.
 *  - **Dies with its parent** — closing the parent closes the satellite;
 *    [onCloseRequest] fires so the caller can drop it from composition.
 *  - **Reparentable** — pass a different [parent] and the satellite moves to
 *    the new owner without changing its position on screen, which is how a
 *    single palette can serve whichever document window is active. This holds
 *    even when the previous owner closes in the same frame: the satellite steps
 *    out of its owner link before the old window is destroyed, so the OS never
 *    takes it down with it.
 *
 * ```kotlin
 * DecoratedWindow(onCloseRequest = ::exitApplication) {
 *     TitleBar { Text("Document") }
 *     Button({ palette = !palette }) { Text("Inspector") }
 *     if (palette) {
 *         SatelliteWindow(
 *             onCloseRequest = { palette = false },
 *             state = rememberSatelliteWindowState(
 *                 size = DpSize(260.dp, 420.dp),
 *                 positioner = WindowPositioner(
 *                     parentAnchor = WindowAnchor.TopRight,
 *                     childAnchor = WindowAnchor.TopLeft,
 *                     offset = DpOffset(12.dp, 0.dp),
 *                 ),
 *             ),
 *             title = "Inspector",
 *         ) {
 *             Inspector()
 *         }
 *     }
 * }
 * ```
 *
 * ### Platform notes
 * Positioning a satellite requires the platform to let a client place its own
 * windows. Native **Wayland** does not (xdg-shell gives the compositor full
 * authority — GDK reports every toplevel at `(0, 0)` and ignores moves), so
 * there the satellite is a plain owned window: correct z-order, ownership,
 * lifetime and hide-while-maximized, but compositor-chosen placement, no
 * follow, and [SatelliteWindowState.offsetFromParent] stays `null` rather than
 * publishing a made-up offset. The window is still draggable, by the
 * compositor's own move. Run with `NUCLEUS_TAO_LINUX_RENDERER=x11`, or give
 * the window `forceX11`, when the anchoring matters. X11, XWayland, Windows
 * and macOS all follow.
 *
 * The work area the [WindowPositioner] keeps the satellite inside is the
 * parent's own monitor on Windows. macOS and Linux fall back to the primary
 * monitor's work area, so a parent on a secondary display whose Dock / panel
 * layout differs may see its satellite flipped or slid against the wrong edge.
 *
 * @param onCloseRequest invoked when the user closes the satellite, and when
 *   its parent is destroyed. Drop the satellite from composition here.
 * @param parent the window the satellite belongs to. Defaults to the enclosing
 *   [DecoratedWindow] via [LocalTaoWindow]; pass it explicitly to anchor to a
 *   window that isn't the one being composed. A `null` parent degrades to a
 *   plain top-level window.
 * @param hideWhileParentFullscreenOrMaximized hide the satellite while the
 *   parent fills the screen instead of floating over it. `true` matches the
 *   Flutter archetype.
 */
@Suppress("LongParameterList", "FunctionNaming", "LongMethod")
@Composable
public fun ApplicationScope.SatelliteWindow(
    onCloseRequest: () -> Unit,
    parent: TaoWindow? = LocalTaoWindow.current,
    state: SatelliteWindowState = rememberSatelliteWindowState(),
    visible: Boolean = true,
    title: String = "",
    icon: Painter? = null,
    resizable: Boolean = true,
    focusable: Boolean = true,
    hideWhileParentFullscreenOrMaximized: Boolean = true,
    onPreviewKeyEvent: (KeyEvent) -> Boolean = { false },
    onKeyEvent: (KeyEvent) -> Boolean = { false },
    // Parent composition locals bridged into the satellite's own ComposeScene
    // from its first composition, exactly like [DecoratedDialog].
    compositionLocalContext: CompositionLocalContext? = null,
    content: @Composable TaoDecoratedWindowScope.() -> Unit,
) {
    val latestContent by rememberUpdatedState(content)
    val latestOnClose by rememberUpdatedState(onCloseRequest)

    // The anchor is computed from the parent's frame, so the satellite waits
    // for the parent to have one. A satellite declared inside its parent's
    // content composes in the very frame the parent window is created: an
    // anchor resolved then is measured against a frame the parent has not been
    // given yet, and the platform maps the satellite there — visibly, at the
    // wrong place, until the settle loop below drags it across. One frame of
    // waiting costs nothing; a palette that flashes in the middle of the screen
    // before snapping beside its document is what users report.
    val parentPlaced = parentHasFrame(parent)
    if (!parentPlaced) return

    // Resolved synchronously, before the native window exists, so
    // DecoratedWindow's position effect applies it *before* show() — the same
    // no-flash ordering DecoratedDialog relies on for its centring. Computed
    // once: WindowState only ever reads its initial position, and a satellite
    // never re-runs its placement on recomposition or reparenting anyway (see
    // [SatelliteWindowState.reanchor]).
    val initialPosition =
        remember {
            parent?.let { anchoredWindowPosition(it, state) } ?: WindowPosition.PlatformDefault
        }
    val windowState =
        rememberWindowState(
            size = state.size,
            position = initialPosition,
        )
    LaunchedEffect(state.size) {
        if (windowState.size != state.size) windowState.size = state.size
    }

    DecoratedWindow(
        onCloseRequest = { latestOnClose() },
        state = windowState,
        title = title,
        icon = icon,
        minimumSize = null,
        // The suppression flag is folded in here rather than pushed to the
        // window imperatively, so a satellite that is *also* toggled by the app
        // has one single source of truth for visibility.
        visible = visible && !state.isHiddenByParent,
        resizable = resizable,
        focusable = focusable,
        alwaysOnTop = false,
        // Utility-window chrome: no maximize affordance, dialog-flavoured
        // border. The owner relationship below is what keeps it off the
        // taskbar and above its parent.
        isDialog = true,
        onPreviewKeyEvent = onPreviewKeyEvent,
        onKeyEvent = onKeyEvent,
        compositionLocalContext = compositionLocalContext,
        content = {
            val satellite = window

            // Runs inside the satellite's own composition, so `window` is the
            // satellite's TaoWindow and its native handle is resolvable.
            val anchoring =
                remember(satellite, parent) {
                    SatelliteAnchoring(
                        satellite = satellite,
                        parent = parent,
                        state = state,
                        hideWhileParentFills = hideWhileParentFullscreenOrMaximized,
                    )
                }

            // The parent's death is observed natively but acted on from
            // composition, so a reparent that lands in the same frame as the
            // old owner's close — "close the document the palette is attached
            // to" — is not mistaken for the satellite's own end of life: by the
            // time this scene recomposes, [parent] already names the new owner.
            // Dying with the parent is the case where it still names the old one.
            var destroyedParent by remember(satellite) { mutableStateOf<TaoWindow?>(null) }
            LaunchedEffect(parent, destroyedParent) {
                if (parent != null && parent === destroyedParent) latestOnClose()
            }

            // Hands keyboard focus back to the parent when the satellite goes
            // away while it is the active window (closed from its own header,
            // docked on a drag release). Win32 only does this by itself for
            // dialogs ended through `EndDialog`; destroying an active owned
            // `WS_OVERLAPPED` window activates the next window in the Z-order,
            // which can belong to another application and sends the parent to
            // the background. Both calls are queued on the event loop in order,
            // so the parent is foreground before the satellite's HWND dies.
            // Skipped when the parent is the one being destroyed, or when the
            // satellite was not focused (an app-driven close must not steal
            // the foreground).
            val currentParent by rememberUpdatedState(parent)
            val currentDestroyedParent by rememberUpdatedState(destroyedParent)
            DisposableEffect(satellite) {
                onDispose {
                    val target = currentParent
                    if (satellite.isFocused && target != null && target !== currentDestroyedParent) target.focus()
                }
            }

            DisposableEffect(anchoring) {
                applyWindowOwnerRelationship(
                    child = satellite,
                    owner = parent,
                    autoCenter = false,
                    destroyWithOwner = false,
                )
                anchoring.onParentDestroyed = { destroyedParent = it }
                anchoring.attach()
                state.reanchorRequest = { anchoring.reanchor() }
                onDispose {
                    anchoring.detach()
                    state.reanchorRequest = null
                }
            }

            // Re-synced on change so flipping the flag while the parent is
            // already maximized takes effect at once, not on its next resize.
            LaunchedEffect(anchoring, hideWhileParentFullscreenOrMaximized) {
                anchoring.setHideWhileParentFills(hideWhileParentFullscreenOrMaximized)
            }

            SettleInitialPlacement(satellite, anchoring)
            RealignAfterSteppingBack(satellite, anchoring, state.isHiddenByParent)

            DisposableEffect(satellite) {
                val listener: (Boolean) -> Unit = { focused -> state.isActive = focused }
                satellite.onFocusChanged(listener)
                onDispose { state.isActive = false }
            }

            latestContent()
        },
    )
}

/**
 * Settles the *initial* placement of [satellite]. A satellite declared inside
 * its parent's content composes in the same frame the parent window is
 * created, before the parent's own position effect has run — so the position
 * it was given can be anchored to a parent rect that is about to change, or to
 * none at all. Re-read real geometry as soon as both windows are mapped; from
 * then on the offset the follow logic preserves is the anchored one.
 *
 * One successful re-anchor is not enough: a window manager can report a real
 * frame at the origin and apply the requested position several frames later
 * (openbox under Xvfb takes tens of milliseconds; a loaded desktop longer).
 * Anchoring to that frame latches an offset measured against a parent that was
 * never there, and the follow logic then preserves it forever — the satellite
 * trails its parent by exactly the distance the parent moved after the map. So
 * keep re-anchoring until the parent's frame has held still for
 * [PLACEMENT_SETTLE_STABLE_POLLS] polls, the only signal a WM gives that
 * placement is done.
 *
 * Keyed on the satellite, not on [anchoring]: a reparent swaps the anchoring
 * but must leave the satellite where it is on screen.
 */
@Suppress("FunctionNaming")
@Composable
private fun SettleInitialPlacement(
    satellite: TaoWindow,
    anchoring: SatelliteAnchoring,
) {
    val current by rememberUpdatedState(anchoring)
    LaunchedEffect(satellite) {
        var placedWith: SatelliteAnchoring? = null
        var anchoredAgainst: List<Long>? = null
        var stablePolls = 0
        var pollsSincePlaced = 0
        repeat(PLACEMENT_SETTLE_ATTEMPTS) {
            val settling = current
            if (!settling.hasParent || !settling.canPlace) return@LaunchedEffect
            // Hard stop once the satellite has been placed: this covers the
            // window manager's map-time placement, which lands within a few
            // frames, and nothing else. A loop still running when the app —
            // or the user — moves the owner would re-anchor instead of
            // letting the follow logic preserve the offset it captured.
            if (placedWith != null && ++pollsSincePlaced > PLACEMENT_SETTLE_POLLS_AFTER_PLACED) {
                return@LaunchedEffect
            }
            // Stop as soon as the anchoring that placed it is no longer the
            // live one. Before the first placement the loop still follows the
            // swap: a satellite reparented before it ever landed has to be
            // placed against whoever owns it now.
            if (placedWith != null && placedWith !== settling) return@LaunchedEffect
            val frame = settling.parentFramePx()
            if (frame != null && frame != anchoredAgainst) {
                // Only a parent frame this satellite has not been anchored
                // against yet is worth another move. Re-anchoring on every
                // poll would keep overriding a placement someone else owns —
                // a satellite reopened where the user had dragged it is
                // positioned by the workspace, not by the positioner.
                if (settling.reanchor()) {
                    placedWith = settling
                    anchoredAgainst = frame
                    stablePolls = 0
                }
            } else if (frame != null && ++stablePolls >= PLACEMENT_SETTLE_STABLE_POLLS) {
                return@LaunchedEffect
            }
            delay(PLACEMENT_SETTLE_POLL_MILLIS)
        }
    }
}

/**
 * Puts [satellite] back at its offset after it stepped aside for a maximized
 * or fullscreen parent.
 *
 * Coming back re-shows the window, and the single move the step-back path
 * issues races that re-map. When it loses, the satellite stays exactly where
 * it was before it stepped aside — right for a parent that never moved, wrong
 * for one that was restored somewhere else. Re-assert the offset until it
 * holds, the same way the first placement settles.
 */
@Suppress("FunctionNaming")
@Composable
private fun RealignAfterSteppingBack(
    satellite: TaoWindow,
    anchoring: SatelliteAnchoring,
    hiddenByParent: Boolean,
) {
    val current by rememberUpdatedState(anchoring)
    var steppedAside by remember(satellite) { mutableStateOf(false) }
    LaunchedEffect(satellite, hiddenByParent) {
        if (hiddenByParent) {
            steppedAside = true
            return@LaunchedEffect
        }
        if (!steppedAside) return@LaunchedEffect
        repeat(PLACEMENT_SETTLE_ATTEMPTS) {
            val settling = current
            if (!settling.hasParent || !settling.canPlace || settling.realignToOffset()) {
                return@LaunchedEffect
            }
            delay(PLACEMENT_SETTLE_POLL_MILLIS)
        }
    }
}

/**
 * Whether [parent] has a real frame to anchor against yet — `true` at once for
 * a parentless satellite, and for a parent that is already on screen.
 *
 * Polled rather than driven by `onMoved` / `onResized`: the frame can be there
 * before either fires (a satellite opened over a window that has been up for
 * minutes), and the wait is bounded so a parent that never maps — hidden, or
 * on a platform that reports no frame at all — still gets its satellite rather
 * than none.
 */
@Composable
private fun parentHasFrame(parent: TaoWindow?): Boolean {
    if (parent == null) return true
    var placed by remember(parent) { mutableStateOf(parent.hasRealFrame()) }
    LaunchedEffect(parent) {
        var attempt = 0
        while (!placed && attempt < PLACEMENT_SETTLE_ATTEMPTS) {
            delay(PLACEMENT_SETTLE_POLL_MILLIS)
            attempt++
            placed = parent.hasRealFrame()
        }
        // Out of patience: show the satellite anyway, wherever the platform
        // puts it, rather than never showing it at all.
        placed = true
    }
    return placed
}

/** `true` once the platform reports a frame with a real size for this window. */
private fun TaoWindow.hasRealFrame(): Boolean {
    val rect = outerBoundsPx() ?: return false
    return rect[2] > 1L && rect[3] > 1L
}

/**
 * Keeps a satellite pinned to its parent.
 *
 * Everything here runs on the Tao event-loop thread (= the Compose dispatcher),
 * so the plain fields need no synchronisation and the Compose state writes are
 * on the right thread.
 *
 * Physical pixels throughout: [TaoWindow.outerBoundsPx] and
 * [TaoWindow.setOuterPositionPx] share one coordinate space, which keeps the
 * follow arithmetic free of any dp ↔ px round-tripping.
 */
private class SatelliteAnchoring(
    private val satellite: TaoWindow,
    private val parent: TaoWindow?,
    private val state: SatelliteWindowState,
    private var hideWhileParentFills: Boolean,
) {
    /** Receives the parent once its native window has been destroyed. */
    var onParentDestroyed: (TaoWindow) -> Unit = {}

    val hasParent: Boolean get() = parent != null

    /**
     * Whether the satellite can be placed on screen at all. `false` on native
     * Wayland, where the follow, the anchoring and the offset capture are all
     * skipped: the rects they would read put every window at the screen
     * origin, and the moves they would issue are ignored. Ownership, z-order
     * and the hide-while-parent-fills rule still apply.
     */
    val canPlace: Boolean get() = satellite.supportsScreenPlacement

    private var offsetXPx = 0
    private var offsetYPx = 0
    private var captured = false

    /** Last position we asked the satellite to move to, and whether it landed. */
    private var commandedXPx = 0
    private var commandedYPx = 0
    private var awaitingCommand = false

    /**
     * Follow moves issued but not yet observed. A parent drag produces a burst
     * of them; only a satellite move seen with the queue empty can be the
     * user's own drag.
     */
    private var inFlight = 0
    private var detached = false

    /** Whether the parent filled the screen last time it was looked at. */
    private var lastFills: Boolean? = null

    /**
     * Set when the satellite is shown again after stepping aside: the parent
     * is on its way out of a maximized or fullscreen frame, and the geometry
     * read at that instant can still be the old one. Cleared by the first
     * parent geometry that lands afterwards, which is the settled one.
     */
    private var realignPending = false

    private val parentMoved: (Int, Int) -> Unit = { xPx, yPx -> onParentMoved(xPx, yPx) }
    private val parentResized: (Int, Int) -> Unit = { _, _ ->
        val wasPending = realignPending
        syncSuppression()
        if (wasPending) realignAfterSteppingBack()
    }
    private val parentMinimized: (Boolean) -> Unit = { minimized -> if (!minimized) reassertOwnership() }
    private val parentFullscreen: (Int, Int, Boolean) -> Unit = { _, _, entering ->
        // Hide before the transition animates so the satellite is never caught
        // hovering over a fullscreen window. Leaving fullscreen is resolved by
        // the resize that follows, when isFullscreen has actually flipped.
        if (entering) syncSuppression(force = true)
    }

    // Owner about to be destroyed: step out of the owner link first. Win32 and
    // GTK destroy owned windows with their owner, which would kill a satellite
    // the app is reparenting in this very frame; whether the satellite then
    // closes or moves on is decided from composition (see onParentDestroyed).
    private val parentClosing: () -> Unit = { if (!detached) clearWindowOwnerRelationship(satellite) }
    private val parentDestroyed: () -> Unit = { if (!detached) parent?.let(onParentDestroyed) }
    private val satelliteMoved: (Int, Int) -> Unit = { xPx, yPx -> onSatelliteMoved(xPx, yPx) }

    fun attach() {
        val owner = parent ?: return
        if (canPlace) {
            captureOffset()
            owner.onMoved(parentMoved)
            satellite.onMoved(satelliteMoved)
        }
        owner.onResized(parentResized)
        owner.onMinimizedChanged(parentMinimized)
        owner.onFullscreenPrepare(parentFullscreen)
        owner.onClosing(parentClosing)
        owner.onDestroyed(parentDestroyed)
        syncSuppression()
    }

    fun detach() {
        detached = true
        satellite.removeMovedListener(satelliteMoved)
        val owner = parent ?: return
        owner.removeMovedListener(parentMoved)
        owner.removeResizedListener(parentResized)
        owner.removeMinimizedListener(parentMinimized)
        owner.removeFullscreenPrepareListener(parentFullscreen)
        owner.removeClosingListener(parentClosing)
        owner.removeDestroyedListener(parentDestroyed)
    }

    /** Updates the suppression rule and re-evaluates it against the parent right away. */
    fun setHideWhileParentFills(hide: Boolean) {
        if (hideWhileParentFills == hide) return
        hideWhileParentFills = hide
        syncSuppression()
    }

    /** The parent's frame as `[x, y, w, h]` physical px, or `null` while it has none. */
    fun parentFramePx(): List<Long>? = parent?.takeIf { it.hasRealFrame() }?.outerBoundsPx()?.toList()

    /** Reads the parent-relative offset off live geometry. `true` once known. */
    fun captureOffset(): Boolean {
        if (captured) return true
        if (detached || !canPlace) return false
        val owner = parent ?: return false
        if (!owner.hasRealFrame() || !satellite.hasRealFrame()) return false
        val parentRect = owner.outerBoundsPx() ?: return false
        val selfRect = satellite.outerBoundsPx() ?: return false
        publishOffset((selfRect[0] - parentRect[0]).toInt(), (selfRect[1] - parentRect[1]).toInt())
        captured = true
        return true
    }

    /**
     * Re-applies the positioner against the parent's current geometry, using
     * the satellite's real frame. `false` while either window is not mapped
     * yet, so a caller can retry.
     */
    fun reanchor(): Boolean {
        if (detached || !canPlace) return false
        val owner = parent ?: return false
        val parentRect = owner.outerBoundsPx() ?: return false
        val selfRect = satellite.outerBoundsPx() ?: return false
        // GTK maps a window at 1x1 until its first allocation, so "has a size"
        // is `> 1`, not `> 0`: anchoring against a 1px-tall satellite centres
        // its *top* on the parent instead of its middle, and the wrong offset
        // is then latched for the lifetime of the pairing.
        if (!satellite.hasRealFrame()) return false
        val childSize = Size(selfRect[2].toFloat(), selfRect[3].toFloat())
        val origin = anchoredOriginPx(owner, state, childSize) ?: return false
        val xPx = origin.x.toInt()
        val yPx = origin.y.toInt()
        publishOffset(xPx - parentRect[0].toInt(), yPx - parentRect[1].toInt())
        captured = true
        command(xPx, yPx)
        return true
    }

    private fun onParentMoved(
        parentXPx: Int,
        parentYPx: Int,
    ) {
        if (detached) return
        if (!captureOffset()) return
        // A hidden satellite is repositioned when it comes back, against the
        // parent's geometry at that point — no need to chase it meanwhile.
        if (state.isHiddenByParent) return
        // This *is* the settled geometry the re-show was waiting for.
        realignPending = false
        command(parentXPx + offsetXPx, parentYPx + offsetYPx)
    }

    private fun onSatelliteMoved(
        xPx: Int,
        yPx: Int,
    ) {
        if (detached) return
        if (!captured) {
            captureOffset()
            return
        }
        if (awaitingCommand &&
            closeEnough(xPx, commandedXPx) &&
            closeEnough(yPx, commandedYPx)
        ) {
            // Caught up with the last follow move.
            awaitingCommand = false
            inFlight = 0
            return
        }
        if (inFlight > 0) {
            // Stale echo from an earlier follow move in the same drag burst.
            inFlight--
            return
        }
        val parentRect = parent?.outerBoundsPx() ?: return
        publishOffset(xPx - parentRect[0].toInt(), yPx - parentRect[1].toInt())
    }

    private fun command(
        xPx: Int,
        yPx: Int,
    ) {
        commandedXPx = xPx
        commandedYPx = yPx
        awaitingCommand = true
        inFlight++
        satellite.setOuterPositionPx(xPx, yPx)
    }

    /**
     * Aligns [SatelliteWindowState.isHiddenByParent] with the parent's
     * placement. [force] hides ahead of a fullscreen transition, before the
     * platform flag has flipped.
     */
    private fun syncSuppression(force: Boolean = false) {
        if (detached) return
        val owner = parent ?: return
        val fills = force || owner.isFullscreen || owner.isMaximized
        val fillsChanged = fills != lastFills
        lastFills = fills
        val hide = hideWhileParentFills && fills
        if (hide != state.isHiddenByParent) {
            state.isHiddenByParent = hide
            if (!hide) {
                // AppKit drops a child window's parent link when the child is
                // ordered out; re-assert it so the satellite comes back above its
                // parent instead of behind it. No-op where the platform keeps the
                // relationship across hide/show.
                reassertOwnership()
                // Re-align while still hidden: the parent may have moved during the
                // fullscreen stint, and the position sticks before the show().
                val parentRect = owner.outerBoundsPx() ?: return
                if (captured) command(parentRect[0].toInt() + offsetXPx, parentRect[1].toInt() + offsetYPx)
                // That frame can still be the maximized one — the platform
                // reports the restore in pieces, and every move it made while
                // the satellite was away was skipped. Re-align on the next one.
                realignPending = true
            }
            return
        }
        // Same visibility on both sides of a maximize / fullscreen / restore —
        // an app that opted out of hiding. The transition re-stacks the owner,
        // which on every platform can leave the satellite *behind* the window
        // it belongs to, so put the link back. While the owner fills the
        // screen this runs on every resize, not only on the flip: the
        // fullscreen prepare hook flips `lastFills` *before* the native
        // transition, so the resize that lands afterwards is the one that
        // has to re-stack.
        if ((fills || fillsChanged) && !state.isHiddenByParent) reassertOwnership()
    }

    /**
     * Puts the satellite back at its captured offset, and reports whether it
     * is there. Unlike [realignAfterSteppingBack] this can be called
     * repeatedly: a move issued while the platform is still re-mapping the
     * window it just re-showed can be dropped outright — GTK carries a move
     * into the map only when it is issued *before* it — so the one command the
     * step-back path sends is not always enough.
     */
    fun realignToOffset(): Boolean {
        if (detached || !canPlace || !captured) return false
        if (state.isHiddenByParent) return false
        val owner = parent ?: return false
        if (owner.isMaximized || owner.isFullscreen) return false
        if (!owner.hasRealFrame() || !satellite.hasRealFrame()) return false
        val parentRect = owner.outerBoundsPx() ?: return false
        val selfRect = satellite.outerBoundsPx() ?: return false
        val targetX = parentRect[0].toInt() + offsetXPx
        val targetY = parentRect[1].toInt() + offsetYPx
        if (closeEnough(selfRect[0].toInt(), targetX) && closeEnough(selfRect[1].toInt(), targetY)) return true
        command(targetX, targetY)
        return false
    }

    /**
     * Puts the satellite back at its offset once the parent's frame has
     * settled after a maximize / fullscreen stint. A no-op unless the
     * satellite has just stepped back in — see [realignPending].
     */
    private fun realignAfterSteppingBack() {
        if (!realignPending || detached || !canPlace || !captured) return
        if (state.isHiddenByParent) return
        val owner = parent ?: return
        val parentRect = owner.outerBoundsPx() ?: return
        if (owner.isMaximized || owner.isFullscreen) return
        realignPending = false
        command(parentRect[0].toInt() + offsetXPx, parentRect[1].toInt() + offsetYPx)
    }

    /**
     * Re-applies the native owner link, which is what keeps the satellite
     * above its parent. Idempotent, and the platform calls behind it are
     * cheap, so it is safe to run on every state transition.
     */
    private fun reassertOwnership() {
        if (detached) return
        val owner = parent ?: return
        applyWindowOwnerRelationship(child = satellite, owner = owner, autoCenter = false, destroyWithOwner = false)
    }

    private fun publishOffset(
        xPx: Int,
        yPx: Int,
    ) {
        offsetXPx = xPx
        offsetYPx = yPx
        val scale = satellite.scaleFactor.takeIf { it > 0f } ?: 1f
        state.offsetFromParent = DpOffset((xPx / scale).dp, (yPx / scale).dp)
    }

    private fun closeEnough(
        actual: Int,
        expected: Int,
    ): Boolean = kotlin.math.abs(actual - expected) <= COMMAND_ECHO_SLOP_PX
}

/**
 * The satellite's anchored top-left corner in physical screen pixels, or `null`
 * when the parent's geometry or the monitor work area is unavailable.
 */
private fun anchoredOriginPx(
    parent: TaoWindow,
    state: SatelliteWindowState,
    childSizePx: Size,
): Offset? {
    val parentRectPx = parent.outerBoundsPx() ?: return null
    // A frame with no size is a window the platform has not laid out yet —
    // 1x1 being GTK's placeholder for it, not a size. Anchoring to its right
    // edge would put the satellite on its left one; `null` makes the caller
    // retry rather than latch onto that.
    if (!parent.hasRealFrame()) return null
    val workAreaPx = parentMonitorWorkAreaPx(parent) ?: return null
    val scale = parent.scaleFactor.takeIf { it > 0f } ?: 1f
    val parentRect = parentRectPx.toRect()
    val anchorRect =
        state.anchorRect?.let { rect ->
            Rect(
                parentRect.left + rect.left.value * scale,
                parentRect.top + rect.top.value * scale,
                parentRect.left + rect.right.value * scale,
                parentRect.top + rect.bottom.value * scale,
            )
        } ?: parentRect
    return state.positioner
        .placeIn(
            childSize = childSizePx,
            anchorRect = anchorRect,
            parentRect = parentRect,
            workArea = workAreaPx.toRect(),
            scale = scale,
        ).topLeft
}

/**
 * The anchored position as a [WindowPosition.Absolute] for the satellite's
 * initial [androidx.compose.ui.window.WindowState], or
 * [WindowPosition.PlatformDefault] when the parent isn't on screen yet.
 *
 * The satellite's native window does not exist at this point, so the placement
 * uses the requested size; once mapped, the follow logic re-reads the real
 * frame, which is what every later move is based on.
 */
private fun anchoredWindowPosition(
    parent: TaoWindow,
    state: SatelliteWindowState,
): WindowPosition {
    // Native Wayland: the parent rect this would anchor to is the screen
    // origin, and the compositor places the window anyway.
    if (!parent.supportsScreenPlacement) return WindowPosition.PlatformDefault
    val scale = parent.scaleFactor.takeIf { it > 0f } ?: 1f
    val childSizePx = Size(state.size.width.value * scale, state.size.height.value * scale)
    val origin = anchoredOriginPx(parent, state, childSizePx) ?: return WindowPosition.PlatformDefault
    // WindowState.position is applied through Tao's logical set_outer_position,
    // which multiplies by the scale the window was created at — the primary
    // monitor's on Windows, the window's own elsewhere. Same conversion as
    // DecoratedDialog's centring, so a satellite on a second monitor with a
    // different DPI still lands where the positioner asked.
    val logicalScale =
        if (Platform.Current == Platform.Windows && NativeTaoWindowsDecoBridge.isLoaded) {
            NativeTaoWindowsDecoBridge.nativeGetPrimaryMonitorScaleMilli().coerceAtLeast(1) / 1000f
        } else {
            scale
        }
    return WindowPosition.Absolute((origin.x / logicalScale).dp, (origin.y / logicalScale).dp)
}

/**
 * Work area of the monitor the parent sits on, falling back to the primary
 * monitor's. Windows exposes the owner's monitor directly; elsewhere the
 * primary work area is the best available answer.
 */
private fun parentMonitorWorkAreaPx(parent: TaoWindow): LongArray? {
    if (Platform.Current == Platform.Windows && NativeTaoWindowsDecoBridge.isLoaded) {
        val hwnd = parent.nativeHandle
        if (hwnd != 0L) {
            NativeTaoWindowsDecoBridge.nativeOwnerMonitorWorkArea(hwnd)?.let { return it }
        }
    }
    return TaoScreenGeometry.primaryMonitorWorkAreaPx(parent)
}

/** `[x, y, w, h]` physical px → a float rect. */
private fun LongArray.toRect(): Rect =
    Rect(
        this[0].toFloat(),
        this[1].toFloat(),
        (this[0] + this[2]).toFloat(),
        (this[1] + this[3]).toFloat(),
    )

/** Physical-pixel slop when matching a follow move against its echo. */
private const val COMMAND_ECHO_SLOP_PX = 2

/** ~1.6 s at 60 Hz — far past any observed map latency, then given up on. */
private const val PLACEMENT_SETTLE_ATTEMPTS = 100
private const val PLACEMENT_SETTLE_POLL_MILLIS = 16L

/** Consecutive identical parent frames that count as "the WM is done placing it". */
private const val PLACEMENT_SETTLE_STABLE_POLLS = 3

/** Upper bound on the settle window once the satellite has been placed once (~190 ms). */
private const val PLACEMENT_SETTLE_POLLS_AFTER_PLACED = 12
