package dev.nucleusframework.window.tao.headful

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowState
import dev.nucleusframework.core.runtime.Platform
import dev.nucleusframework.window.tao.ApplicationScope
import dev.nucleusframework.window.tao.DockLayout
import dev.nucleusframework.window.tao.JoinSatelliteWorkspace
import dev.nucleusframework.window.tao.LocalTaoWindow
import dev.nucleusframework.window.tao.Satellite
import dev.nucleusframework.window.tao.SatellitePlacement
import dev.nucleusframework.window.tao.SatelliteWorkspace
import dev.nucleusframework.window.tao.TaoWindow
import dev.nucleusframework.window.tao.WindowAnchor
import dev.nucleusframework.window.tao.WindowConstraintAdjustment
import dev.nucleusframework.window.tao.WindowPositioner
import java.awt.MouseInfo
import java.awt.event.InputEvent
import kotlin.math.abs
import kotlin.math.roundToInt

/** Everything one case observes; fresh per case, so cases never share windows or state. */
internal class SatelliteWorkspaceFixture {
    val workspace = SatelliteWorkspace()

    /** The satellite's own window while floating (the content's [LocalTaoWindow]). */
    val floatingWindow = mutableStateOf<TaoWindow?>(null)

    /** The host window while docked. */
    val panelHost = mutableStateOf<TaoWindow?>(null)

    /** Docked panel rect in host window px, and the host content size at that time. */
    val panelBoundsPx = mutableStateOf<Rect?>(null)
    val hostContentSizePx = mutableStateOf<IntSize?>(null)

    /** Content rect of the DockLayout's own content slot, in host window px. */
    val contentBoundsPx = mutableStateOf<Rect?>(null)

    /**
     * A plain `remember` living in the DockLayout's *content* — the document,
     * not the satellite. It survives only as long as that subtree keeps its
     * identity, which is what docking a first panel must not disturb.
     */
    val documentState = mutableStateOf<MutableState<Int>?>(null)

    /** The `rememberSaveable` counter of the current host's composition. */
    val counter = mutableStateOf<MutableState<Int>?>(null)

    /** Hosts currently composing the content; the two overlap for a frame when switching. */
    val composedHosts = mutableIntStateOf(0)
    val isComposed: Boolean get() = composedHosts.value > 0

    @Composable
    fun ApplicationScope.ToolsSatellite() {
        Satellite(
            workspace = workspace,
            id = SATELLITE_ID,
            title = "Tools",
            initialPlacement =
                SatellitePlacement.Floating(
                    positioner = workspaceRightEdgePositioner(),
                    size = workspaceSatelliteSize(),
                ),
        ) {
            val clicks = rememberSaveable { mutableStateOf(0) }
            val window = LocalTaoWindow.current
            val docked = isDocked
            val container = LocalWindowInfo.current.containerSize
            SideEffect {
                counter.value = clicks
                if (docked) {
                    panelHost.value = window
                    hostContentSizePx.value = container
                } else {
                    floatingWindow.value = window
                }
            }
            DisposableEffect(docked) {
                composedHosts.value++
                onDispose {
                    composedHosts.value--
                    // Cleared on the way out, so a case waiting for the panel
                    // cannot pass on a host published by an earlier dock — and
                    // the same for the floating window. Only when the value
                    // still names *this* host, though: a panel moved from one
                    // window's dock straight into another's keeps `docked`
                    // true on both sides, and the new host publishes itself
                    // before the old one is disposed.
                    if (docked) {
                        if (panelHost.value === window) panelHost.value = null
                    } else if (floatingWindow.value === window) {
                        floatingWindow.value = null
                    }
                }
            }
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color(0xFF2D6CDF))
                    .onGloballyPositioned { if (docked) panelBoundsPx.value = it.boundsInWindow() },
            )
        }
    }

    /** Window content: join the workspace, host the dock around a plain body. */
    @Composable
    fun Body() {
        JoinSatelliteWorkspace(workspace)
        DockLayout(workspace, Modifier.fillMaxSize()) {
            val kept = remember { mutableStateOf(0) }
            SideEffect { documentState.value = kept }
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.DarkGray)
                    .onGloballyPositioned { contentBoundsPx.value = it.boundsInWindow() },
            )
        }
    }
}

internal fun workspaceParentWindowState() =
    WindowState(
        position = WindowPosition.Absolute(PARENT_X_DP.dp, PARENT_Y_DP.dp),
        size = DpSize(PARENT_W_DP.dp, PARENT_H_DP.dp),
    )

internal fun workspaceRightEdgePositioner() =
    WindowPositioner(
        parentAnchor = WindowAnchor.Right,
        childAnchor = WindowAnchor.Left,
        offset = DpOffset(GAP_DP.dp, 0.dp),
        constraintAdjustment = WindowConstraintAdjustment.None,
    )

internal fun workspaceSatelliteSize() = DpSize(SATELLITE_W_DP.dp, SATELLITE_H_DP.dp)

/**
 * Real press and drag from [from] to [to] (physical screen px) with the AWT
 * Robot, which speaks logical screen points. The button stays **down** so the
 * caller can assert the in-flight state — the dock preview, the ghost —
 * before [robotRelease] drops it; asserting only after the drop races the
 * gesture and picks up whatever position the last processed move had.
 *
 * [steps] and [stepDelayMillis] shape the path: the defaults are a deliberate
 * drag, `steps = 3, stepDelayMillis = 0` is a flick the OS coalesces into a
 * couple of enormous deltas. `null` when the host cannot inject input.
 */
internal suspend fun robotPressAndDrag(
    from: Offset,
    to: Offset,
    scale: Float,
    steps: Int = ROBOT_DRAG_STEPS,
    stepDelayMillis: Long = ROBOT_DRAG_STEP_MILLIS,
): Boolean? =
    HeadfulRobot.inject { robot ->
        fun x(p: Offset) = (p.x / scale).roundToInt()

        fun y(p: Offset) = (p.y / scale).roundToInt()
        robot.mouseMove(x(from), y(from))
        Thread.sleep(ROBOT_PRESS_SETTLE_MILLIS)
        HeadfulRobot.noteAim(x(from), y(from))
        robot.mousePress(InputEvent.BUTTON1_DOWN_MASK)
        Thread.sleep(ROBOT_PRESS_SETTLE_MILLIS)
        for (step in 1..steps) {
            val t = step / steps.toFloat()
            robot.mouseMove(x(from + (to - from) * t), y(from + (to - from) * t))
            if (stepDelayMillis > 0) Thread.sleep(stepDelayMillis)
        }
        true
    }

/**
 * Continues the gesture [robotPressAndDrag] is holding: interpolates from
 * wherever the pointer is now to [to] (physical screen px) without touching
 * the button, so a case can hover one target and then another before dropping.
 * `null` when the host cannot inject input.
 */
internal suspend fun robotDragTo(
    to: Offset,
    scale: Float,
    steps: Int = ROBOT_DRAG_STEPS,
    stepDelayMillis: Long = ROBOT_DRAG_STEP_MILLIS,
): Boolean? =
    HeadfulRobot.inject { robot ->
        val targetX = (to.x / scale).roundToInt()
        val targetY = (to.y / scale).roundToInt()
        val start = MouseInfo.getPointerInfo()?.location
        if (start == null) {
            robot.mouseMove(targetX, targetY)
        } else {
            for (step in 1..steps) {
                val t = step / steps.toFloat()
                robot.mouseMove(
                    (start.x + (targetX - start.x) * t).roundToInt(),
                    (start.y + (targetY - start.y) * t).roundToInt(),
                )
                if (stepDelayMillis > 0) Thread.sleep(stepDelayMillis)
            }
        }
        true
    }

/**
 * Where the last robot gesture aimed and where the pointer landed — worth
 * putting in the description of anything a robot-driven case waits for, so a
 * timeout on a runner nobody can attach to still says which of the two went
 * wrong.
 */
internal fun robotAim(): String = HeadfulRobot.lastAimReport

/** Drops what [robotPressAndDrag] is holding. */
internal suspend fun robotRelease(): Boolean? =
    HeadfulRobot.inject { robot ->
        robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK)
        true
    }

/** Waits until the floating satellite window is mapped and anchored to the current owner. */
internal suspend fun TaoWindowTestScope.awaitFloating(fixture: SatelliteWorkspaceFixture): TaoWindow {
    awaitUntil("owner window mapped") { bounds() != null }
    awaitUntil("floating satellite mapped with a real size") {
        fixture.floatingWindow.value?.hasRealFramePx() == true
    }
    awaitUntil("satellite captured its owner offset") {
        fixture.workspace
            .satellite(SATELLITE_ID)
            ?.windowState
            ?.offsetFromParent != null
    }
    settle(SETTLE_AFTER_MAP_MILLIS)
    return requireNotNull(fixture.floatingWindow.value)
}

/** Moves [owner] and checks the floating satellite keeps its offset from it. */
internal suspend fun TaoWindowTestScope.awaitFollows(
    fixture: SatelliteWorkspaceFixture,
    owner: TaoWindow,
    label: String,
) {
    awaitUntil("offset to the $label captured") {
        fixture.workspace
            .satellite(SATELLITE_ID)
            ?.windowState
            ?.offsetFromParent != null
    }
    settle()
    val ownerBefore = requireNotNull(owner.outerBoundsPx())
    val satelliteBefore = requireNotNull(requireNotNull(fixture.floatingWindow.value).outerBoundsPx())
    val offsetX = satelliteBefore[0] - ownerBefore[0]
    val offsetY = satelliteBefore[1] - ownerBefore[1]
    val scale = owner.scaleFactor.toDouble()
    owner.setOuterPosition(ownerBefore[0] / scale + MOVE_DELTA_DP, ownerBefore[1] / scale + MOVE_DELTA_DP)
    awaitUntil("$label moved") {
        val now = owner.outerBoundsPx() ?: return@awaitUntil false
        now[0] != ownerBefore[0] || now[1] != ownerBefore[1]
    }
    awaitUntil("satellite followed the $label") {
        val ownerNow = owner.outerBoundsPx() ?: return@awaitUntil false
        val satelliteNow = fixture.floatingWindow.value?.outerBoundsPx() ?: return@awaitUntil false
        abs((satelliteNow[0] - ownerNow[0]) - offsetX) <= FOLLOW_TOLERANCE_PX &&
            abs((satelliteNow[1] - ownerNow[1]) - offsetY) <= FOLLOW_TOLERANCE_PX
    }
}

/**
 * Native Wayland has no client-side toplevel positioning, so neither the
 * anchored placement nor the follow is observable there.
 */
internal fun workspaceSkipReason(): String? {
    if (Platform.Current != Platform.Linux) return null
    val backend = System.getenv("GDK_BACKEND")?.split(',')?.firstOrNull()
    val forcedX11 =
        backend == "x11" ||
            System.getenv("NUCLEUS_TAO_LINUX_RENDERER").orEmpty().equals("x11", ignoreCase = true)
    val wayland = System.getenv("WAYLAND_DISPLAY") != null && !forcedX11
    return if (wayland) "no client window positioning on Wayland (xdg-shell)" else null
}

internal const val SATELLITE_ID = "tools"
internal const val SAVED_CLICKS = 3
internal const val DOCUMENT_MARK = 7
internal const val PARENT_X_DP = 120
internal const val PARENT_Y_DP = 90
internal const val PARENT_W_DP = 520
internal const val PARENT_H_DP = 360
internal const val SATELLITE_W_DP = 220
internal const val SATELLITE_H_DP = 160
internal const val DIALOG_W_DP = 300
internal const val DIALOG_H_DP = 240
internal const val GAP_DP = 10
internal const val MOVE_DELTA_DP = 70.0

internal const val ANCHOR_TOLERANCE_PX = 6L
internal const val FOLLOW_TOLERANCE_PX = 8L
internal const val LAYOUT_TOLERANCE_PX = 4f

/** Rounding only: both sides of the comparison come from the same live geometry. */
internal const val EXACT_TOLERANCE_PX = 4.0

/** Client-origin estimate vs. real frame, plus the lift-off's own rounding. */
internal const val LIFT_OFF_TOLERANCE_PX = 24.0

/** Vertical grab point inside a header strip, in dp from its top. */
internal const val HEADER_GRAB_Y_DP = 15f

/**
 * Vertical grab point in the title bar *above* the header strip, in dp from
 * the window's top. The header centres itself in the bar, so a few dp down is
 * bar and not strip.
 *
 * Past the resize edge band, deliberately: `ResizeFrameDecoration` claims the
 * top 5 logical px of a resizable window, and it is right to — three px from
 * the top edge of a palette is a resize grip on every desktop. A window whose
 * frame adds nothing above its content (Tao on X11, Win32) puts that band
 * exactly where a grab measured from the outer frame lands, which is why this
 * has to clear it rather than sit "a few dp down".
 */
internal const val TITLE_BAR_TOP_GRAB_DP = 8f
internal const val DROP_INSET_PX = 20f
internal const val ROBOT_DRAG_STEPS = 12
internal const val ROBOT_DRAG_STEP_MILLIS = 40L
internal const val ROBOT_PRESS_SETTLE_MILLIS = 150L
internal const val SETTLE_AFTER_MAP_MILLIS = 400L

/** Enough dock/undock rounds to expose a leak, few enough to stay quick. */
internal const val CHURN_CYCLES = 6
internal const val JUMP_SETTLE_MILLIS = 60L
internal const val RESIZED_W_DP = 620.0
internal const val RESIZED_H_DP = 430.0
internal const val RESIZE_TOLERANCE_PX = 48L

/** A flick: as few samples as the OS will deliver. */
internal const val FLICK_STEPS = 3
internal const val GRAB_INSET_PX = 12f
internal const val DRAG_AWAY_PX = 180f

/** Far enough right of a layout that no dock zone of any window is under it. */
internal const val DROP_FAR_PX = 420f
