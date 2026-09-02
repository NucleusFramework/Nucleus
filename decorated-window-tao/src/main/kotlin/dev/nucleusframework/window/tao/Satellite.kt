package dev.nucleusframework.window.tao

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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
) : SatelliteScope

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
 * The workspace remembers the satellite ([SatelliteEntry]) after this
 * composable leaves composition, so [initialPlacement] and [initiallyOpen]
 * only apply the first time an [id] is declared (and never when a
 * [SatelliteWorkspace.restore] already placed it).
 *
 * @param id stable identity within the workspace.
 * @param title shown by the default [header] and as the floating window title.
 * @param initialPlacement where the satellite starts on first declaration.
 * @param initiallyOpen whether it is shown on first declaration.
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
 * @param content the satellite's body.
 */
@Suppress("LongParameterList", "FunctionNaming")
@Composable
public fun ApplicationScope.Satellite(
    workspace: SatelliteWorkspace,
    id: String,
    title: String,
    initialPlacement: SatellitePlacement = SatellitePlacement.Floating(),
    initiallyOpen: Boolean = true,
    resizable: Boolean = true,
    hideWhileOwnerFullscreenOrMaximized: Boolean = true,
    compositionLocalContext: CompositionLocalContext? = null,
    floatingContentWrapper: @Composable TaoDecoratedWindowScope.(content: @Composable () -> Unit) -> Unit = { it() },
    header: @Composable SatelliteScope.() -> Unit = { DefaultSatelliteHeader() },
    content: @Composable SatelliteScope.() -> Unit,
) {
    val entry = remember(workspace, id) { workspace.register(id, title, initialPlacement, initiallyOpen) }
    val scope = remember(entry) { SatelliteScopeImpl(workspace, entry, isDocked = false) }
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
            SatelliteGhostCard(ghost.satellite.title)
        }
    }

    val placement = entry.placement
    val owner = workspace.owner
    if (!entry.isOpen || !workspace.visible || placement !is SatellitePlacement.Floating || owner == null) return

    val currentHeader by rememberUpdatedState(header)
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
                            modifier = Modifier.satelliteDragHandle(scope),
                            layoutPolicy = TitleBarLayoutPolicy.FillCenter,
                            nativeWindowDrag = false,
                        ) {
                            Box(Modifier.fillMaxWidth()) { currentHeader(scope) }
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
 * The translucent card a panel torn out of its dock is previewed as: the
 * satellite's grip and title on a tinted, rounded surface, filling the ghost
 * window.
 */
@Composable
private fun SatelliteGhostCard(title: String) {
    val accent = LocalTitleBarStyle.current.colors.content
    val ghostShape = RoundedCornerShape(GHOST_CORNER_DP.dp)
    Box(
        Modifier
            .fillMaxSize()
            .background(accent.copy(alpha = GHOST_FILL_ALPHA), ghostShape)
            .border(GHOST_BORDER_DP.dp, accent.copy(alpha = GHOST_BORDER_ALPHA), ghostShape),
    ) {
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
 * No-op outside a Tao window. Drives [SatelliteWorkspace.beginDrag].
 */
public fun Modifier.satelliteDragHandle(scope: SatelliteScope): Modifier =
    screenDragHandle(
        key = scope,
        isDragging = { scope.workspace.draggedSatellite === scope.satellite },
    ) { window, pointerScreenPx ->
        val origin =
            if (scope.isDocked) {
                SatelliteDragOrigin.DockedPanel(window)
            } else {
                SatelliteDragOrigin.FloatingWindow(window)
            }
        scope.workspace.beginDrag(scope.satellite.id, origin, pointerScreenPx)?.asScreenDrag()
    }

private fun SatelliteDragSession.asScreenDrag(): ScreenDrag =
    object : ScreenDrag {
        override fun update(pointerScreenPx: Offset) = this@asScreenDrag.update(pointerScreenPx)

        override fun end(pointerScreenPx: Offset) = this@asScreenDrag.end(pointerScreenPx)

        override fun cancel() = this@asScreenDrag.cancel()
    }

/**
 * The stock satellite header: the title, then "Dock" while floating or
 * "Float" and "Close" while docked. Colours come from [LocalTitleBarStyle], so
 * it matches whatever title-bar theme the app installed.
 *
 * Dragging it moves the satellite between windows and docks. While docked the
 * strip carries the [satelliteDragHandle] itself; while floating it does not,
 * because the title bar it sits in already is one — a second handle nested
 * inside the first would start two drags for one gesture.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
public fun SatelliteScope.DefaultSatelliteHeader() {
    val colors = LocalTitleBarStyle.current.colors
    var hovered by remember { mutableStateOf(false) }
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .then(if (isDocked) Modifier.satelliteDragHandle(this) else Modifier)
                .onPointerEvent(PointerEventType.Enter) { hovered = true }
                .onPointerEvent(PointerEventType.Exit) { hovered = false }
                .background(if (hovered) colors.content.copy(alpha = GRIP_HOVER_ALPHA) else Color.Transparent)
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
            HeaderAction("Float", colors.content) { undock() }
            HeaderAction("Close", colors.content) { close() }
        } else {
            HeaderAction("Dock", colors.content) { dock() }
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
private const val GRIP_WIDTH_DP = 7
private const val GRIP_HEIGHT_DP = 13
private const val GRIP_GAP_DP = 8
private const val GRIP_DOT_RADIUS_DP = 1
private const val GRIP_DOT_COLUMNS = 2
private const val GRIP_DOT_ROWS = 3
private const val GRIP_ALPHA = 0.55f
private const val GRIP_HOVER_ALPHA = 0.08f
private const val GHOST_FILL_ALPHA = 0.22f
private const val GHOST_BORDER_ALPHA = 0.55f
private const val GHOST_BORDER_DP = 1
private const val GHOST_CORNER_DP = 8
private const val GHOST_PADDING_DP = 8
private const val HEADER_ACTION_PADDING_DP = 6
private const val HEADER_ACTION_VERTICAL_PADDING_DP = 2
private const val HEADER_TITLE_SP = 13
private const val HEADER_ACTION_SP = 12
