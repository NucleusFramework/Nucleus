package dev.nucleusframework.window.tao

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.foundation.gestures.drag
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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.currentCompositeKeyHashCode
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState
import dev.nucleusframework.window.BasicTitleBar
import dev.nucleusframework.window.TitleBarLayoutPolicy
import dev.nucleusframework.window.WindowScaffold
import dev.nucleusframework.window.styling.LocalTitleBarStyle

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
        SatelliteDragGhostWindow(ghost, compositionLocalContext)
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
                        // FillCenter hands its single centre child exactly the
                        // width left between the platform controls (traffic
                        // lights inset, caption buttons) — the header is a strip,
                        // not a centred title.
                        BasicTitleBar(layoutPolicy = TitleBarLayoutPolicy.FillCenter) {
                            Box(Modifier.fillMaxWidth()) { currentHeader(scope) }
                        }
                    },
                ) { padding ->
                    Box(Modifier.fillMaxSize().padding(padding)) {
                        SatelliteStateHost(entry, scope)
                    }
                }
            }
        }
    }
}

/**
 * The borderless, click-through window that previews a panel being dragged out
 * of its dock: a translucent card of the panel's size, following the pointer
 * across (and out of) the window it is being torn from.
 *
 * A real window rather than an overlay drawn inside the host, because the whole
 * point is that it leaves the host's bounds. It never takes focus and never
 * takes the pointer, so the drag gesture keeps running in the window underneath.
 */
@Suppress("FunctionNaming")
@Composable
private fun ApplicationScope.SatelliteDragGhostWindow(
    ghost: DragGhost,
    compositionLocalContext: CompositionLocalContext?,
) {
    val rect = ghost.screenRectPx
    // The host's scale, not this composition's: the application scope the
    // ghost is composed in belongs to no window, so its density is always 1.
    val scale = ghost.scaleFactor.takeIf { it > 0f } ?: 1f
    val state =
        rememberWindowState(
            position = WindowPosition.Absolute((rect.left / scale).dp, (rect.top / scale).dp),
            size = DpSize((rect.width / scale).dp, (rect.height / scale).dp),
        )
    // Reactive follow: the drag session republishes the rect on every pointer
    // move, and DecoratedWindow pushes state changes to the native window.
    SideEffect {
        state.position = WindowPosition.Absolute((rect.left / scale).dp, (rect.top / scale).dp)
        state.size = DpSize((rect.width / scale).dp, (rect.height / scale).dp)
    }
    val accent = LocalTitleBarStyle.current.colors.content
    val ghostShape = RoundedCornerShape(GHOST_CORNER_DP.dp)
    DecoratedWindow(
        onCloseRequest = {},
        state = state,
        title = ghost.satellite.title,
        undecorated = true,
        transparent = true,
        resizable = false,
        focusable = false,
        clickThrough = true,
        alwaysOnTop = true,
        compositionLocalContext = compositionLocalContext,
    ) {
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
                    text = ghost.satellite.title,
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
}

/**
 * Hosts the satellite's content under a saveable-state registry owned by the
 * satellite, so
 * `rememberSaveable` values follow the satellite from one host to the next.
 *
 * Two things make this more than a shared `SaveableStateHolder`:
 *
 *  - The two hosts live in different compositions (the floating window's
 *    scene and the dock host's scene) whose dispose / compose order in the
 *    switching frame is not defined. The new host therefore pulls the live
 *    values straight out of the registry that is still mounted, falling back
 *    to the values the previous host saved on dispose — correct in both orders.
 *  - `rememberSaveable` keys are the composite key hash of the call site,
 *    which encodes the whole path from the root of the composition — and the
 *    path differs between hosts. [RelocatingSaveableStateRegistry] maps the
 *    keys across using the hash recorded at this composable, see there.
 */
@Composable
internal fun SatelliteStateHost(
    entry: SatelliteEntry,
    scope: SatelliteScope,
) {
    val anchor: Long = currentCompositeKeyHashCode
    val registry =
        remember(entry) {
            val saved = entry.activeRegistry?.snapshot() ?: entry.savedState
            RelocatingSaveableStateRegistry(saved, anchor).also { entry.activeRegistry = it }
        }
    DisposableEffect(registry) {
        onDispose {
            entry.savedState = registry.snapshot()
            if (entry.activeRegistry === registry) entry.activeRegistry = null
        }
    }
    // The user's content is invoked from here, and only from here, in both
    // hosts: every group between the anchor above and the content's own
    // rememberSaveable call sites is then identical, which is what the key
    // relocation in RelocatingSaveableStateRegistry relies on.
    val content = entry.content ?: return
    CompositionLocalProvider(LocalSaveableStateRegistry provides registry) {
        content(scope)
    }
}

/**
 * `rememberSaveable` values saved by one host, with the composite key hash of
 * the [SatelliteStateHost] they were composed under ([anchor]).
 */
internal class SatelliteSavedState(
    val anchor: Long,
    val values: Map<String, List<Any?>>,
)

/**
 * A [SaveableStateRegistry] that restores values saved under a *different*
 * composition path.
 *
 * Compose derives a `rememberSaveable` key from the composite key hash, built
 * top-down as `hash = (hash rol shift) xor segment` for every group entered,
 * and rendered in radix 36. For the same content composed below two anchors
 * `A` and `B`, a call site at the same relative position therefore hashes to
 * `kA` and `kB` with `kA xor kB == (A xor B) rol n` for some `n` (the shifts
 * accumulated on the way down). The hash is 64-bit on the JVM, so there are
 * at most 64 candidates for that rotation — [consumeRestored] matches a
 * requested key against the saved ones by testing exactly that, after trying
 * an exact match (same host, or explicit string keys) first.
 *
 * Only the linearity of the hash is relied on, not the shift constants or the
 * group structure, so the mapping is exact as long as the content composes the
 * same `rememberSaveable` call sites in both hosts, which it does by
 * construction.
 */
internal class RelocatingSaveableStateRegistry(
    saved: SatelliteSavedState?,
    private val anchor: Long,
) : SaveableStateRegistry {
    /**
     * One registered provider. Several call sites can share a key — Compose
     * then stores a *list* per key and hands the values back in composition
     * order — so a slot keeps its position in that list for the lifetime of
     * the host, whether its provider is still registered or not.
     */
    private class Slot(
        var provider: (() -> Any?)?,
    ) {
        /** Value read out of [provider] when it unregistered. */
        var captured: Any? = null
    }

    private val slots = LinkedHashMap<String, MutableList<Slot>>()
    private val pending: MutableMap<String, MutableList<Any?>> =
        saved?.values.orEmpty().mapValuesTo(LinkedHashMap()) { (_, values) -> values.toMutableList() }
    private val rotations: Set<Long> =
        saved?.let { previous ->
            val delta = previous.anchor xor anchor
            (0 until Long.SIZE_BITS).mapTo(HashSet()) { delta.rotateLeft(it) }
        } ?: emptySet()

    override fun consumeRestored(key: String): Any? {
        val match = if (key in pending) key else relocatedKey(key) ?: return null
        val values = pending.getValue(match)
        val value = values.removeAt(0)
        if (values.isEmpty()) pending.remove(match)
        return value
    }

    private fun relocatedKey(key: String): String? {
        if (rotations.isEmpty()) return null
        val requested = key.toLongOrNull(KEY_RADIX) ?: return null
        return pending.keys.firstOrNull { candidate ->
            val saved = candidate.toLongOrNull(KEY_RADIX) ?: return@firstOrNull false
            (saved xor requested) in rotations
        }
    }

    override fun registerProvider(
        key: String,
        valueProvider: () -> Any?,
    ): SaveableStateRegistry.Entry {
        val keySlots = slots.getOrPut(key) { mutableListOf() }
        // Reuse a vacated slot before growing the list: a recomposing
        // `rememberSaveable` unregisters and registers again under the same
        // key, and must not shift the values of its neighbours.
        val slot =
            keySlots.firstOrNull { it.provider == null }?.apply { provider = valueProvider }
                ?: Slot(valueProvider).also { keySlots += it }
        return object : SaveableStateRegistry.Entry {
            override fun unregister() {
                slot.captured = slot.provider?.invoke()
                slot.provider = null
            }
        }
    }

    override fun canBeSaved(value: Any): Boolean = true

    /**
     * Every value this host knows, per key, in registration order.
     *
     * Order is the whole contract when several call sites share a key, and it
     * cannot be read off the providers still registered: when a host is
     * disposed Compose unregisters them in reverse composition order, and it
     * does so *before* the host's own disposable effect runs. Hence the slots,
     * which hold their position and keep the value their provider had on the
     * way out.
     *
     * Keys restored but never consumed are carried over, so a satellite that
     * moves hosts twice before its content composes keeps its state.
     */
    override fun performSave(): Map<String, List<Any?>> {
        val map = LinkedHashMap<String, List<Any?>>()
        for ((key, values) in pending) map[key] = values.toList()
        for ((key, keySlots) in slots) {
            map[key] = keySlots.map { slot -> slot.provider?.invoke() ?: slot.captured }
        }
        return map
    }

    /** Everything this host knows, tagged with its anchor. */
    fun snapshot(): SatelliteSavedState = SatelliteSavedState(anchor, performSave())

    private companion object {
        /** `rememberSaveable` renders the composite key hash in this radix. */
        const val KEY_RADIX = 36
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
 * No-op outside a Tao window. Drives [SatelliteWorkspace.beginDrag].
 */
public fun Modifier.satelliteDragHandle(scope: SatelliteScope): Modifier =
    composed {
        val window = LocalTaoWindow.current ?: return@composed Modifier
        val containerSize = LocalWindowInfo.current.containerSize
        var coordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
        val dragging = scope.workspace.draggedSatellite === scope.satellite
        Modifier
            // Open hand, closed hand while dragging: the desktop's own idiom
            // for "pick this up". Compose only defines four icons in common
            // code, none of which says "draggable".
            .pointerHoverIcon(if (dragging) TaoPointerIcons.Grabbing else TaoPointerIcons.Grab)
            .onGloballyPositioned { coordinates = it }
            .pointerInput(scope, window, containerSize) {
                /** Pointer position in this element → physical screen pixels. */
                fun screenPx(local: Offset): Offset? {
                    val inWindow = coordinates?.localToWindow(local) ?: return null
                    val outer = window.outerBoundsPx() ?: return null
                    return clientOriginPx(outer, containerSize) + inWindow
                }
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    // Claimed in the Main pass: the title bar's native drag arms
                    // on an unconsumed press in the Final pass.
                    down.consume()
                    val start =
                        awaitTouchSlopOrCancellation(down.id) { change, _ -> change.consume() }
                            ?: return@awaitEachGesture
                    var pointer = screenPx(start.position) ?: return@awaitEachGesture
                    val origin =
                        if (scope.isDocked) {
                            SatelliteDragOrigin.DockedPanel(window)
                        } else {
                            SatelliteDragOrigin.FloatingWindow(window)
                        }
                    val session =
                        scope.workspace.beginDrag(scope.satellite.id, origin, pointer) ?: return@awaitEachGesture
                    try {
                        session.update(pointer)
                        val released =
                            drag(start.id) { change ->
                                change.consume()
                                screenPx(change.position)?.let {
                                    pointer = it
                                    session.update(it)
                                }
                            }
                        if (released) session.end(pointer) else session.cancel()
                    } finally {
                        // The pointer-input coroutine is cancelled whenever this
                        // modifier is re-keyed or detached — a window resize
                        // mid-drag does it — and neither branch above would run.
                        // Without this the zone hints and the ghost would stay
                        // on screen for good. No-op once the session is done.
                        session.cancel()
                    }
                }
            }
    }

/**
 * The stock satellite header: the title, then "Dock" while floating or
 * "Float" and "Close" while docked. The whole strip is a
 * [satelliteDragHandle], so dragging it moves the satellite between windows
 * and docks. Colours come from [LocalTitleBarStyle], so it matches whatever
 * title-bar theme the app installed.
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
                .satelliteDragHandle(this)
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
