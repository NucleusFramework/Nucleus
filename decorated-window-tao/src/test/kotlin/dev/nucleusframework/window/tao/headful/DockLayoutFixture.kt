package dev.nucleusframework.window.tao.headful

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.nucleusframework.window.tao.ApplicationScope
import dev.nucleusframework.window.tao.DefaultDockSideOrder
import dev.nucleusframework.window.tao.DefaultDockSplitter
import dev.nucleusframework.window.tao.DockLayout
import dev.nucleusframework.window.tao.DockSide
import dev.nucleusframework.window.tao.DockSplitterScope
import dev.nucleusframework.window.tao.JoinSatelliteWorkspace
import dev.nucleusframework.window.tao.LocalTaoWindow
import dev.nucleusframework.window.tao.Satellite
import dev.nucleusframework.window.tao.SatellitePlacement
import dev.nucleusframework.window.tao.SatelliteScope
import dev.nucleusframework.window.tao.SatelliteWorkspace
import dev.nucleusframework.window.tao.TaoWindow
import kotlin.math.abs

/** One satellite the fixture declares: its id and where it starts. */
internal class DockPanelSpec(
    val id: String,
    val placement: SatellitePlacement,
    val open: Boolean = true,
    val dockSides: Set<DockSide> = DockSide.entries.toSet(),
    val floatable: Boolean = true,
    val reorderable: Boolean = true,
)

/**
 * A `DockLayout` under observation: every panel body, every splitter and the
 * content publish their window-px bounds, their layout direction and how many
 * times they were built, so a case can assert on geometry the way a user sees
 * it and on composition identity the way a `remember` experiences it.
 *
 * The layout's shape — side order, layered sides, direction — is state, so a
 * case can change it mid-run and check what survived.
 */
internal class DockLayoutFixture(
    val specs: List<DockPanelSpec>,
    sideOrder: List<DockSide> = DefaultDockSideOrder,
    layeredSides: Set<DockSide> = emptySet(),
    direction: LayoutDirection = LayoutDirection.Ltr,
    /** Draw the splitter as a 1 dp line whose grip is a wider, overflowing box. */
    val gripOverflow: Boolean = false,
) {
    val workspace = SatelliteWorkspace()
    val sideOrder = mutableStateOf(sideOrder)
    val layeredSides = mutableStateOf(layeredSides)
    val direction = mutableStateOf(direction)

    /** Bounds of each panel's `panel` slot (header and body), in host window px. */
    val panelBounds = mutableStateOf<Map<String, Rect>>(emptyMap())

    /** Bounds of each docked panel's body, in host window px. */
    val bodyBounds = mutableStateOf<Map<String, Rect>>(emptyMap())

    /** Bounds of each splitter grip, keyed by [splitterKey], in host window px. */
    val splitterBounds = mutableStateOf<Map<String, Rect>>(emptyMap())

    /** Bounds of the layout's content slot, in host window px. */
    val contentBounds = mutableStateOf<Rect?>(null)
    val contentDirection = mutableStateOf<LayoutDirection?>(null)
    val bodyDirections = mutableStateOf<Map<String, LayoutDirection>>(emptyMap())

    /** What each satellite's chrome was told about its window: `isCompositorPlaced`, per host kind. */
    val compositorPlacedDocked = mutableStateOf<Map<String, Boolean>>(emptyMap())
    val compositorPlacedFloating = mutableStateOf<Map<String, Boolean>>(emptyMap())

    /** Bounds of each satellite's `floatingCaption` slot, in its own window px; absent while not composed. */
    val captionBounds = mutableStateOf<Map<String, Rect>>(emptyMap())

    /** The floating window of each satellite while it floats. */
    val floatingWindows = mutableStateOf<Map<String, TaoWindow>>(emptyMap())

    /** How many times each satellite's body was built, and how many are live right now. */
    val incarnations = mutableStateOf<Map<String, Int>>(emptyMap())
    val liveBodies = mutableStateOf<Map<String, Int>>(emptyMap())
    val contentIncarnations = mutableIntStateOf(0)

    private var nextMarker = 0

    fun incarnationsOf(id: String): Int = incarnations.value[id] ?: 0

    fun liveBodiesOf(id: String): Int = liveBodies.value[id] ?: 0

    /** The `splitterBounds` key of a splitter: the panel it resizes, or the side it drags. */
    fun splitterKey(scope: DockSplitterScope): String = scope.panel?.let { "panel:${it.id}" } ?: "side:${scope.side}"

    fun splitterOf(id: String): Rect? = splitterBounds.value["panel:$id"]

    fun sideSplitterOf(side: DockSide): Rect? = splitterBounds.value["side:$side"]

    /** Window content: join the workspace, host the dock around a plain body. */
    @Composable
    fun Body() {
        JoinSatelliteWorkspace(workspace)
        CompositionLocalProvider(LocalLayoutDirection provides direction.value) {
            DockLayout(
                workspace = workspace,
                modifier = Modifier.fillMaxSize(),
                sideOrder = sideOrder.value,
                layeredSides = layeredSides.value,
                splitter = { Splitter(this) },
                panel = { body ->
                    val id = satellite.id
                    DisposableEffect(id) {
                        onDispose { panelBounds.value = panelBounds.value - id }
                    }
                    Box(
                        Modifier
                            .fillMaxSize()
                            .onGloballyPositioned {
                                panelBounds.value = panelBounds.value + (id to it.boundsInWindow())
                            },
                    ) { body() }
                },
            ) {
                remember { contentIncarnations.value++ }
                val here = LocalLayoutDirection.current
                SideEffect { contentDirection.value = here }
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Color.DarkGray)
                        .onGloballyPositioned { contentBounds.value = it.boundsInWindow() },
                )
            }
        }
    }

    @Composable
    private fun Splitter(scope: DockSplitterScope) {
        val key = splitterKey(scope)
        DisposableEffect(key) {
            onDispose { splitterBounds.value = splitterBounds.value - key }
        }
        val record =
            Modifier.onGloballyPositioned {
                splitterBounds.value =
                    splitterBounds.value + (key to it.boundsInWindow())
            }
        with(scope) {
            if (gripOverflow) {
                val horizontal = orientation == Orientation.Horizontal
                val line =
                    if (horizontal) {
                        Modifier.fillMaxHeight().width(
                            1.dp,
                        )
                    } else {
                        Modifier.fillMaxWidth().height(1.dp)
                    }
                Box(line.background(Color.Red), contentAlignment = Alignment.Center) {
                    val grip =
                        if (horizontal) {
                            Modifier.requiredWidth(GRIP_OVERFLOW_DP.dp).fillMaxHeight()
                        } else {
                            Modifier.requiredHeight(GRIP_OVERFLOW_DP.dp).fillMaxWidth()
                        }
                    Box(grip.then(record).dockSplitterHandle())
                }
            } else {
                Box(record) { DefaultDockSplitter() }
            }
        }
    }

    @Composable
    fun ApplicationScope.Satellites() {
        for (spec in specs) {
            key(spec.id) {
                Satellite(
                    workspace = workspace,
                    id = spec.id,
                    title = "Panel ${spec.id}",
                    initialPlacement = spec.placement,
                    initiallyOpen = spec.open,
                    floatingCaption = {
                        DisposableEffect(spec.id) {
                            onDispose { captionBounds.value = captionBounds.value - spec.id }
                        }
                        Box(
                            Modifier
                                .fillMaxSize()
                                .onGloballyPositioned {
                                    captionBounds.value = captionBounds.value + (spec.id to it.boundsInWindow())
                                },
                        )
                    },
                    dockSides = spec.dockSides,
                    floatable = spec.floatable,
                    reorderable = spec.reorderable,
                ) { PanelBody(spec.id) }
            }
        }
    }

    /**
     * A body that tells the case whether it is the same one as before: the
     * marker is a plain `remember`, so it survives exactly as long as the
     * subtree does.
     */
    @Composable
    private fun SatelliteScope.PanelBody(id: String) {
        val marker = remember { nextMarker++ }
        val window = LocalTaoWindow.current
        val docked = isDocked
        val here = LocalLayoutDirection.current
        val placed = isCompositorPlaced
        SideEffect {
            if (docked) {
                compositorPlacedDocked.value = compositorPlacedDocked.value + (id to placed)
            } else {
                compositorPlacedFloating.value = compositorPlacedFloating.value + (id to placed)
            }
            bodyDirections.value = bodyDirections.value + (id to here)
            if (!docked && window != null) floatingWindows.value = floatingWindows.value + (id to window)
        }
        DisposableEffect(marker) {
            incarnations.value = incarnations.value + (id to (incarnations.value[id] ?: 0) + 1)
            liveBodies.value = liveBodies.value + (id to (liveBodies.value[id] ?: 0) + 1)
            onDispose {
                liveBodies.value = liveBodies.value + (id to (liveBodies.value[id] ?: 0) - 1)
                if (!docked && floatingWindows.value[id] === window) floatingWindows.value = floatingWindows.value - id
                if (docked) bodyBounds.value = bodyBounds.value - id
            }
        }
        Box(
            Modifier
                .fillMaxSize()
                .background(PANEL_COLORS[abs(id.hashCode()) % PANEL_COLORS.size])
                .onGloballyPositioned { if (docked) bodyBounds.value = bodyBounds.value + (id to it.boundsInWindow()) },
        )
    }
}

/** Waits until every satellite in [ids] has a docked body with a real size in the case window. */
internal suspend fun TaoWindowTestScope.awaitDockedBodies(
    fixture: DockLayoutFixture,
    vararg ids: String,
) {
    awaitUntil("owner window mapped") { bounds() != null }
    awaitUntil("panels ${ids.toList()} are docked with a size — have ${fixture.bodyBounds.value.keys}") {
        ids.all { id ->
            val rect = fixture.bodyBounds.value[id]
            rect != null && rect.width > 0f && rect.height > 0f
        }
    }
    awaitDockLayout(fixture.workspace, window)
    settle()
}

/**
 * [awaitDockedBodies] without the screen half: waits for the bodies and for
 * the layout's bounds *in the window*, which is all a native Wayland host can
 * publish.
 */
internal suspend fun TaoWindowTestScope.awaitDockedBodiesInWindow(
    fixture: DockLayoutFixture,
    vararg ids: String,
) {
    awaitUntil("owner window mapped") { bounds() != null }
    awaitUntil("panels ${ids.toList()} are docked with a size — have ${fixture.bodyBounds.value.keys}") {
        ids.all { id ->
            val rect = fixture.bodyBounds.value[id]
            rect != null && rect.width > 0f && rect.height > 0f
        }
    }
    awaitUntil("dock layout of the host is measured in its window") {
        fixture.workspace
            .dockHostGeometry(window)
            ?.layoutBoundsInWindowPx
            ?.isEmpty == false
    }
    settle()
}

/** Screen position (physical px) of a point given in the case window's content coordinates. */
internal fun TaoWindowTestScope.toScreen(
    fixture: DockLayoutFixture,
    inWindowPx: Offset,
): Offset {
    val client = requireNotNull(fixture.workspace.dockHostGeometry(window)?.clientOriginPx()) { "no client origin" }
    return client + inWindowPx
}

/** `true` when [a] and [b] share any area beyond a rounding line. */
internal fun overlaps(
    a: Rect,
    b: Rect,
): Boolean =
    a.left < b.right - LAYOUT_TOLERANCE_PX &&
        b.left < a.right - LAYOUT_TOLERANCE_PX &&
        a.top < b.bottom - LAYOUT_TOLERANCE_PX &&
        b.top < a.bottom - LAYOUT_TOLERANCE_PX

internal fun near(
    a: Float,
    b: Float,
    tolerance: Float = LAYOUT_TOLERANCE_PX,
): Boolean = abs(a - b) <= tolerance

/** The grip's width around the 1 dp line, in dp. */
internal const val GRIP_OVERFLOW_DP = 7

private val PANEL_COLORS =
    listOf(
        Color(0xFF2D6CDF),
        Color(0xFF7A5CD6),
        Color(0xFF2E9E6B),
        Color(0xFFD97B2B),
        Color(0xFFC94C6A),
    )
