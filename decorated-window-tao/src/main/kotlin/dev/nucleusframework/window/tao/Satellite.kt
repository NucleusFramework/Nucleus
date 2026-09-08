// #636: the window openers below are `@ComposableOpenTarget(-1)` with
// `@UiComposable` content lambdas — callable from any applier, always composing
// UI — so a non-UI composable called in the caller's scope cannot reclassify
// the window content. ktlint's `annotation` and `function-type-modifier-spacing`
// rules contradict each other on the resulting two-annotation parameter type.
@file:Suppress("ktlint:standard:annotation")

package dev.nucleusframework.window.tao

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ComposableOpenTarget
import androidx.compose.runtime.CompositionLocalContext
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.UiComposable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.nucleusframework.window.BasicTitleBar
import dev.nucleusframework.window.TitleBarLayoutPolicy
import dev.nucleusframework.window.WindowScaffold
import dev.nucleusframework.window.styling.LocalTitleBarStyle
import dev.nucleusframework.window.tao.workspace.DragGhostWindow
import dev.nucleusframework.window.tao.workspace.RelocatedContentHost
import dev.nucleusframework.window.tao.workspace.ScreenDrag
import dev.nucleusframework.window.tao.workspace.screenDragHandle

/**
 * What a satellite's `header` and `content` lambdas get to see: the satellite
 * itself, its workspace, and the three actions a palette chrome needs.
 *
 * The same scope instance serves both hosts, so a header written once shows
 * "Dock" while floating and "Float" / "Close" while docked without knowing
 * which window it is being composed into.
 */
public interface SatelliteScope {
    /** The workspace the satellite belongs to. */
    public val workspace: SatelliteWorkspace

    /** The satellite being composed. */
    public val satellite: SatelliteEntry

    /**
     * `true` when this composition is the panel inside a [DockLayout], `false`
     * when it is the floating window — a property of the host, not of
     * [SatelliteEntry.placement], so the content never sees the other host's
     * value during the frame in which the two swap.
     */
    public val isDocked: Boolean

    /**
     * `true` when the window this satellite is composed in is placed by the
     * compositor rather than by the app ([TaoWindow.canPlaceOnScreen] `false`
     * — a native Wayland surface).
     *
     * That is the one thing chrome has to adapt to, and it says nothing about
     * the platform: where it holds, *moving the window* is the compositor's
     * gesture and only the area an app leaves unclaimed can start it, while
     * *moving the satellite* — docking it, tearing it out — rides the
     * platform's drag-and-drop session from a [Modifier.satelliteDragHandle].
     * The two cannot share one area, so a floating satellite's title bar
     * reserves [SatelliteCaptionStripWidth] for the compositor and hands it to
     * the `floatingCaption` slot of [Satellite]; everywhere else the whole bar
     * drags the satellite and that slot is not composed at all.
     *
     * `false` until the native window exists, and while the satellite has no
     * window at all (a docked panel reads its host's value).
     */
    public val isCompositorPlaced: Boolean

    /** Docks the satellite on [side] of the workspace owner; defaults to the last side it was docked on. */
    public fun dock(side: DockSide = satellite.preferredDockSide) {
        workspace.dock(satellite.id, side)
    }

    /** Lifts the satellite out of its dock into a floating window. */
    public fun undock() {
        workspace.undock(satellite.id)
    }

    /** Hides the satellite until [SatelliteWorkspace.open]. */
    public fun close() {
        workspace.close(satellite.id)
    }
}

internal class SatelliteScopeImpl(
    override val workspace: SatelliteWorkspace,
    override val satellite: SatelliteEntry,
    override val isDocked: Boolean,
    /**
     * The window this scope's content is composed in, read on every access:
     * the scope outlives the window (a satellite docks, undocks, moves host)
     * and a window answers [TaoWindow.canPlaceOnScreen] only once its native
     * surface exists.
     */
    private val host: () -> TaoWindow? = { null },
) : SatelliteScope {
    override val isCompositorPlaced: Boolean get() = host()?.canPlaceOnScreen == false
}

/**
 * Declares a satellite of [workspace] and hosts it wherever its placement
 * says: as a [SatelliteWindow] owned by the workspace's current owner while
 * floating, or — while docked — inside the [DockLayout] of the window it is
 * docked into. Only one host composes the [content] at a time.
 *
 * Declare it once, at application scope, next to the windows that join the
 * workspace:
 *
 * ```kotlin
 * val workspace = rememberSatelliteWorkspace()
 * DecoratedWindow(onCloseRequest = ::exitApplication) {
 *     JoinSatelliteWorkspace(workspace)
 *     DockLayout(workspace) { Document() }
 * }
 * Satellite(workspace, id = "tools", title = "Tools") { ToolsPanel() }
 * ```
 *
 * `rememberSaveable` state inside [content] survives docking and undocking:
 * the workspace carries it from one host to the next. Plain `remember` state
 * does not, exactly as when any composable moves between windows — hoist it
 * or make it saveable.
 *
 * On native **Wayland** the dock drag rides the platform's drag-and-drop
 * session from the header strip: a reduced picture of the palette follows the
 * pointer instead of the window, and releasing it in a dock zone docks the
 * satellite (see [Modifier.satelliteDragHandle]). The palette is moved by the
 * caption strip beside its window controls, the compositor's own drag.
 * `NUCLEUS_TAO_LINUX_RENDERER=x11` restores the window-following gesture of
 * the other platforms.
 *
 * The workspace remembers the satellite ([SatelliteEntry]) after this
 * composable leaves composition, so [initialPlacement] and [initiallyOpen]
 * only apply the first time an [id] is declared (and never when a
 * [SatelliteWorkspace.restore] already placed it).
 *
 * @param id stable identity within the workspace.
 * @param title shown by the default [header] and as the floating window title.
 * @param initialPlacement where the satellite starts on first declaration.
 * @param initiallyOpen whether it is shown on first declaration.
 * @param dockSides the sides the satellite may be docked on: the others are
 *   neither offered while it is dragged nor accepted by
 *   [SatelliteWorkspace.dock]. Empty makes it a floating-only palette. Fixed
 *   on first declaration, like the placement; a docked [initialPlacement]
 *   must name one of them.
 * @param floatable whether the satellite can be a window of its own. `false`
 *   is a fixed panel: no tear-out, [SatelliteWorkspace.undock] refuses it,
 *   the default header offers no Float action, and a drag can only move it
 *   inside the dock. Requires a docked [initialPlacement].
 * @param reorderable whether the user may change its rank on its side.
 *   `false` pins it to the rank it was declared with: its own drag is offered
 *   none, and another panel can be dropped after it but never in front of it.
 *   Requires a docked [initialPlacement]. With `floatable = false` and a
 *   single [dockSides], the panel is furniture and its header is not even a
 *   drag handle.
 * @param resizable whether the floating window can be resized by the user.
 * @param hideWhileOwnerFullscreenOrMaximized hide the floating window while
 *   the owner fills the screen; see [SatelliteWindow].
 * @param compositionLocalContext parent locals bridged into the floating
 *   window's own scene, as for [SatelliteWindow]. Docked content composes
 *   inside the host window and needs no bridge.
 * @param floatingContentWrapper composed around the floating window's chrome
 *   and content, inside the window's own scene — the hook framework layers
 *   use to provide their per-window locals. Must invoke the lambda it is given.
 * @param header chrome shown in the floating window's title bar and above the
 *   docked panel; [DefaultSatelliteHeader] draws the title and dock actions.
 * @param floatingCaption composed inside the strip of the floating title bar
 *   that is left to the compositor's window move — the
 *   [SatelliteCaptionStripWidth] beside the window controls, reserved only
 *   where the window is placed by the compositor
 *   ([SatelliteScope.isCompositorPlaced]). It is *not* a
 *   [Modifier.satelliteDragHandle]: a press in it moves the window, so what
 *   belongs here is the affordance that says so, not a control. Not composed
 *   at all on the platforms where the whole bar drags the satellite.
 * @param content the satellite's body.
 */
@Suppress("LongParameterList", "FunctionNaming")
@Composable
@ComposableOpenTarget(-1)
public fun ApplicationScope.Satellite(
    workspace: SatelliteWorkspace,
    id: String,
    title: String,
    initialPlacement: SatellitePlacement = SatellitePlacement.Floating(),
    initiallyOpen: Boolean = true,
    dockSides: Set<DockSide> = DockSide.entries.toSet(),
    floatable: Boolean = true,
    reorderable: Boolean = true,
    resizable: Boolean = true,
    hideWhileOwnerFullscreenOrMaximized: Boolean = true,
    compositionLocalContext: CompositionLocalContext? = null,
    floatingContentWrapper:
        @Composable @UiComposable TaoDecoratedWindowScope.(content: @Composable @UiComposable () -> Unit) -> Unit =
        { it() },
    header: @Composable @UiComposable SatelliteScope.() -> Unit = { DefaultSatelliteHeader() },
    floatingCaption: @Composable @UiComposable SatelliteScope.() -> Unit = {},
    content: @Composable @UiComposable SatelliteScope.() -> Unit,
) {
    val entry =
        remember(workspace, id) {
            workspace.register(id, title, initialPlacement, initiallyOpen, dockSides, floatable, reorderable)
        }
    // The satellite's own window, once it has one: the scope is created before
    // it and survives it, so it is read through a lambda.
    var floatingWindow by remember(entry) { mutableStateOf<TaoWindow?>(null) }
    val scope = remember(entry) { SatelliteScopeImpl(workspace, entry, isDocked = false) { floatingWindow } }
    // Published as snapshot state so the DockLayout hosting the panel picks up
    // a new lambda without this composable knowing where the panel lives.
    SideEffect {
        entry.title = title
        entry.header = header
        entry.content = content
    }
    DisposableEffect(workspace, entry) {
        onDispose { workspace.unregister(entry) }
    }

    // Before the early return below: the ghost belongs to a satellite that is
    // *docked* — it is the preview of it being torn out.
    workspace.dragGhost?.takeIf { it.satellite === entry }?.let { ghost ->
        DragGhostWindow(
            screenRectPx = ghost.screenRectPx,
            scaleFactor = ghost.scaleFactor,
            title = ghost.satellite.title,
            compositionLocalContext = compositionLocalContext,
        ) {
            SatelliteGhostCard(ghost.satellite.title, Modifier.fillMaxSize())
        }
    }

    val placement = entry.placement
    val owner = workspace.owner
    if (!entry.isOpen || !workspace.visible || placement !is SatellitePlacement.Floating || owner == null) return

    val currentHeader by rememberUpdatedState(header)

    // Where the satellite actually is, recorded as its placement the moment
    // its window goes away. Closing one keeps "its placement and state" — and
    // the placement a user recognises is where they dragged it to, not the rule
    // it was declared with. Same for the workspace-wide `visible` sweep, which
    // takes every palette down and brings it back.
    DisposableEffect(workspace, entry) {
        onDispose { workspace.recordFloatingPlacement(entry) }
    }

    SatelliteWindow(
        onCloseRequest = { workspace.close(id) },
        parent = owner,
        state = entry.windowState,
        title = title,
        resizable = resizable,
        hideWhileParentFullscreenOrMaximized = hideWhileOwnerFullscreenOrMaximized,
        compositionLocalContext = compositionLocalContext,
    ) {
        val windowScope: TaoDecoratedWindowScope = this
        SideEffect { floatingWindow = window }
        DisposableEffect(window) {
            onDispose { if (floatingWindow === window) floatingWindow = null }
        }
        // Native Wayland: the workspace cannot move the window itself (no
        // client-side placement), so the bar keeps the compositor's move —
        // the only way the palette stays draggable there. The header strip
        // then carries the dock drag over the platform DnD session, and a
        // caption strip next to the window controls is left to the compositor
        // move: the split Chrome's tab strip makes between a tab and the empty
        // strip beside it.
        val workspaceDrag = window.canPlaceOnScreen
        val currentCaption by rememberUpdatedState(floatingCaption)
        floatingContentWrapper {
            with(windowScope) {
                WindowScaffold(
                    titleBar = {
                        // The whole bar is the drag handle, not just the strip
                        // the header draws: the bar is taller than the header,
                        // and the platform move that would otherwise own those
                        // few dp is a compositor grab, so a satellite moved
                        // there could never dock on release. A palette gives up
                        // OS snapping for that; see `nativeWindowDrag`.
                        //
                        // FillCenter hands its single centre child exactly the
                        // width left between the platform controls (traffic
                        // lights inset, caption buttons) — the header is a strip,
                        // not a centred title.
                        BasicTitleBar(
                            modifier = if (workspaceDrag) Modifier.satelliteDragHandle(scope) else Modifier,
                            layoutPolicy = TitleBarLayoutPolicy.FillCenter,
                            nativeWindowDrag = !workspaceDrag,
                        ) {
                            if (workspaceDrag) {
                                Box(Modifier.fillMaxWidth()) { currentHeader(scope) }
                            } else {
                                Row(Modifier.fillMaxWidth().fillMaxHeight()) {
                                    // Full height on purpose: the header strip
                                    // wraps its content and would leave the rest
                                    // of the bar to the compositor move, so half
                                    // a press aimed at the strip would move the
                                    // window instead of starting the dock drag.
                                    Box(
                                        modifier = Modifier.weight(1f).fillMaxHeight().satelliteDragHandle(scope),
                                        contentAlignment = Alignment.Center,
                                    ) { currentHeader(scope) }
                                    // Unclaimed on purpose: the bar's compositor
                                    // move is what a press here starts, and the
                                    // app's own content for it goes inside.
                                    Box(
                                        modifier = Modifier.width(SatelliteCaptionStripWidth).fillMaxHeight(),
                                        contentAlignment = Alignment.Center,
                                    ) { currentCaption(scope) }
                                }
                            }
                        }
                    },
                ) { padding ->
                    Box(Modifier.fillMaxSize().padding(padding)) {
                        RelocatedContentHost(entry.stateSlot, scope, entry.content)
                    }
                }
            }
        }
    }
}

/**
 * The card a panel is previewed as while it is dragged — following the pointer
 * out of its dock, and drawn on the space a release will fill: the satellite's
 * grip and title on the shared [DragPreviewSurface].
 */
@Composable
internal fun SatelliteGhostCard(
    title: String,
    modifier: Modifier = Modifier,
) {
    val accent = LocalTitleBarStyle.current.colors.content
    DragPreviewSurface(modifier) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(GHOST_PADDING_DP.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DragGrip(accent)
            BasicText(
                text = title,
                modifier = Modifier.padding(start = GRIP_GAP_DP.dp),
                style =
                    TextStyle(
                        color = accent,
                        fontSize = HEADER_TITLE_SP.sp,
                        fontWeight = FontWeight.Medium,
                    ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * Makes this element the grip that drags the satellite between its hosts.
 *
 * Dragging a floating satellite moves its window along with the pointer; a
 * docked one shows an outline following the pointer. In both cases the dock
 * zones of every window in the workspace light up as the pointer enters them
 * ([SatelliteWorkspace.dockPreview]), and releasing:
 *
 *  - in a zone docks the satellite there (or re-docks it, from another side
 *    or another window);
 *  - anywhere else, from a dock, lifts the panel out as a window under the
 *    pointer; from a floating window, just leaves it where it was dropped.
 *
 * The pointer turns into an open hand over the grip and a closed one while
 * dragging, and a press without movement does nothing, so buttons can sit
 * inside it.
 * The press is claimed, which keeps an enclosing title bar from starting the
 * native window move instead (see `Modifier.noWindowDrag`) — the window is
 * moved by the workspace so the drop can be decided from the pointer position,
 * at the cost of the OS's own snapping while a satellite is dragged.
 *
 * A floating satellite's title bar already carries this handle across its
 * whole surface, so custom chrome for one needs it only on elements *outside*
 * that bar. A docked panel's header needs it.
 *
 * On native **Wayland** the gesture rides the platform's drag-and-drop
 * session instead, since xdg-shell gives a client neither its windows' screen
 * position nor a way to place them: a reduced picture of the palette follows
 * the pointer, the dock zones of the window the pointer is over light up, and
 * releasing in one docks the satellite there. The floating window itself does
 * not follow — it stays where it is. There the handle covers the header strip
 * of the floating title bar rather than the whole bar, and the caption strip
 * beside the window controls keeps the compositor's move, so the palette can
 * still be moved. Custom floating chrome gets the same split for free: it is
 * composed inside that handle.
 *
 * No-op outside a Tao window, and on a satellite a drag could not move
 * anywhere — fixed to one side, pinned to its rank and alone in the workspace
 * — rather than leaving a gesture that can only end where it started.
 *
 * Drives [SatelliteWorkspace.beginDrag].
 */
public fun Modifier.satelliteDragHandle(scope: SatelliteScope): Modifier =
    if (!scope.workspace.canBeDragged(scope.satellite)) {
        this
    } else {
        screenDragHandle(
            key = scope,
            isDragging = { scope.workspace.draggedSatellite === scope.satellite },
            beginTransfer = { window ->
                scope.workspace.beginTransferDrag(scope.satellite.id, scope.dragOrigin(window))
            },
        ) { window, pointerScreenPx ->
            scope.workspace.beginDrag(scope.satellite.id, scope.dragOrigin(window), pointerScreenPx)?.asScreenDrag()
        }
    }

private fun SatelliteScope.dragOrigin(window: TaoWindow): SatelliteDragOrigin =
    if (isDocked) SatelliteDragOrigin.DockedPanel(window) else SatelliteDragOrigin.FloatingWindow(window)

private fun SatelliteDragSession.asScreenDrag(): ScreenDrag =
    object : ScreenDrag {
        override fun update(pointerScreenPx: Offset) = this@asScreenDrag.update(pointerScreenPx)

        override fun end(pointerScreenPx: Offset) = this@asScreenDrag.end(pointerScreenPx)

        override fun cancel() = this@asScreenDrag.cancel()
    }

/**
 * Width of the strip a floating satellite's title bar leaves to the
 * compositor's window move, next to the window controls, on a window the
 * compositor places ([SatelliteScope.isCompositorPlaced]). The
 * `floatingCaption` slot of [Satellite] is composed inside it.
 *
 * Wide enough to aim at without looking, narrow enough to leave the header
 * the rest of the bar — the same bargain Chrome's tab strip makes with the
 * empty strip beside the last tab.
 */
public val SatelliteCaptionStripWidth: Dp = 56.dp

/**
 * The stock satellite header: the title, then "Dock" while floating or
 * "Float" and "Close" while docked. Colours come from [LocalTitleBarStyle], so
 * it matches whatever title-bar theme the app installed.
 *
 * Dragging it moves the satellite between windows and docks. While docked the
 * strip carries the [satelliteDragHandle] itself; while floating it does not,
 * because the title bar it sits in already is one — a second handle nested
 * inside the first would start two drags for one gesture.
 *
 * On a floating satellite whose title bar it shares with the compositor's
 * window move (native Wayland), the strip is drawn as a rounded chip instead
 * of blending into the bar: there the part that drags into a dock and the
 * part that moves the window are two places, and the user has to be able to
 * see which is which. Chrome's tab strip and GIMP's dock tabs draw the same
 * distinction for the same reason.
 */

@OptIn(ExperimentalComposeUiApi::class)
@Composable
public fun SatelliteScope.DefaultSatelliteHeader() {
    val colors = LocalTitleBarStyle.current.colors
    var hovered by remember { mutableStateOf(false) }
    val window = LocalTaoWindow.current
    val chip = !isDocked && isCompositorPlaced
    val shape = if (chip) RoundedCornerShape(CHIP_CORNER_DP.dp) else RectangleShape
    val background =
        when {
            chip && hovered -> colors.content.copy(alpha = CHIP_HOVER_ALPHA)
            chip -> colors.content.copy(alpha = CHIP_ALPHA)
            hovered -> colors.content.copy(alpha = GRIP_HOVER_ALPHA)
            else -> Color.Transparent
        }
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                // Docked, the strip sizes itself: the dock frame imposes no
                // height, so a custom header can be as tall as it likes.
                // Floating, full height so the whole header strip is the grip,
                // not just the band its content happens to occupy. The chip is
                // inset inside that, so it reads as an object sitting in the
                // bar while the area a press lands on stays the whole strip.
                .then(
                    if (isDocked) {
                        Modifier.height(DockPanelHeaderHeight).background(colors.background)
                    } else {
                        Modifier.fillMaxHeight()
                    },
                ).then(if (chip) Modifier.padding(vertical = CHIP_INSET_DP.dp) else Modifier)
                .then(if (isDocked) Modifier.satelliteDragHandle(this) else Modifier)
                .onPointerEvent(PointerEventType.Enter) { hovered = true }
                .onPointerEvent(PointerEventType.Exit) { hovered = false }
                .background(background, shape)
                .padding(horizontal = HEADER_PADDING_DP.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DragGrip(colors.content)
        BasicText(
            text = satellite.title,
            modifier = Modifier.weight(1f).padding(start = GRIP_GAP_DP.dp),
            style = TextStyle(color = colors.content, fontSize = HEADER_TITLE_SP.sp, fontWeight = FontWeight.Medium),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (isDocked) {
            if (satellite.isFloatable) HeaderAction("Float", colors.content) { undock() }
            HeaderAction("Close", colors.content) { close() }
        } else {
            if (satellite.dockSides.isNotEmpty()) HeaderAction("Dock", colors.content) { dock() }
        }
    }
}

/** Two columns of dots: the "this strip can be dragged" glyph. */
@Composable
private fun DragGrip(color: Color) {
    Canvas(Modifier.size(width = GRIP_WIDTH_DP.dp, height = GRIP_HEIGHT_DP.dp)) {
        val dot = GRIP_DOT_RADIUS_DP.dp.toPx()
        val stepX = size.width - dot * 2
        val stepY = (size.height - dot * 2) / (GRIP_DOT_ROWS - 1)
        for (column in 0 until GRIP_DOT_COLUMNS) {
            for (row in 0 until GRIP_DOT_ROWS) {
                drawCircle(
                    color = color.copy(alpha = GRIP_ALPHA),
                    radius = dot,
                    center = Offset(dot + column * stepX, dot + row * stepY),
                )
            }
        }
    }
}

@Composable
private fun HeaderAction(
    label: String,
    color: Color,
    onClick: () -> Unit,
) {
    // `clickable` consumes the press, which is what opts a title-bar child out
    // of the window drag — same contract as the built-in TitleBar's buttons.
    Box(
        modifier =
            Modifier
                .clickable(onClick = onClick)
                .padding(horizontal = HEADER_ACTION_PADDING_DP.dp, vertical = HEADER_ACTION_VERTICAL_PADDING_DP.dp),
    ) {
        BasicText(text = label, style = TextStyle(color = color, fontSize = HEADER_ACTION_SP.sp))
    }
}

private const val HEADER_PADDING_DP = 8

/** The chip's corner radius, matching the tab strip's own tabs. */
private const val CHIP_CORNER_DP = 8

/** Gap between the chip and the bar's edges, so it reads as sitting inside it. */
private const val CHIP_INSET_DP = 4
private const val CHIP_ALPHA = 0.14f
private const val CHIP_HOVER_ALPHA = 0.22f
private const val GRIP_WIDTH_DP = 7
private const val GRIP_HEIGHT_DP = 13
private const val GRIP_GAP_DP = 8
private const val GRIP_DOT_RADIUS_DP = 1
private const val GRIP_DOT_COLUMNS = 2
private const val GRIP_DOT_ROWS = 3
private const val GRIP_ALPHA = 0.55f
private const val GRIP_HOVER_ALPHA = 0.08f
private const val GHOST_PADDING_DP = 8
private const val HEADER_ACTION_PADDING_DP = 6
private const val HEADER_ACTION_VERTICAL_PADDING_DP = 2
private const val HEADER_TITLE_SP = 13
private const val HEADER_ACTION_SP = 12
