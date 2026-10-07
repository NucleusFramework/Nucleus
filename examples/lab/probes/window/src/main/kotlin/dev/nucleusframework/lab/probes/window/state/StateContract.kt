package dev.nucleusframework.lab.probes.window.state

import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.nucleusframework.lab.core.format.fmt
import dev.nucleusframework.lab.core.mvi.Reducer
import dev.nucleusframework.lab.core.mvi.pushFront
import dev.nucleusframework.lab.probes.window.shared.MonitorRow
import dev.nucleusframework.lab.probes.window.shared.WindowSignal
import dev.nucleusframework.lab.probes.window.shared.WindowSnapshot
import dev.nucleusframework.lab.probes.window.shared.expectedTopLeft
import dev.nucleusframework.window.tao.TaoWindow
import kotlin.math.abs

enum class PlacementChoice { Floating, Maximized, Fullscreen }

enum class PositionTarget(
    val label: String,
    val alignment: Alignment,
) {
    TopStart("↖", Alignment.TopStart),
    TopCenter("↑", Alignment.TopCenter),
    TopEnd("↗", Alignment.TopEnd),
    CenterStart("←", Alignment.CenterStart),
    Center("·", Alignment.Center),
    CenterEnd("→", Alignment.CenterEnd),
    BottomStart("↙", Alignment.BottomStart),
    BottomCenter("↓", Alignment.BottomCenter),
    BottomEnd("↘", Alignment.BottomEnd),
}

enum class SizePreset(
    val label: String,
    val size: DpSize,
) {
    Small("480×320", DpSize(480.dp, 320.dp)),
    Medium("800×560", DpSize(800.dp, 560.dp)),
    Large("1200×800", DpSize(1200.dp, 800.dp)),
}

/** Parameters of the session window; [hiddenFromDock] is creation-time and reopens it. */
@Immutable
data class WindowFlags(
    val title: String = "State lab",
    val resizable: Boolean = true,
    val minimizable: Boolean = true,
    val maximizable: Boolean = true,
    val alwaysOnTop: Boolean = false,
    val alwaysOnBottom: Boolean = false,
    val focusable: Boolean = true,
    val enabled: Boolean = true,
    val visibleOnAllWorkspaces: Boolean = false,
    val minSize: Boolean = false,
    val maxSize: Boolean = false,
    val hiddenFromDock: Boolean = false,
)

/** What the v2 `WindowState` reports back (dp, as the app sees it). */
@Immutable
data class V2Readback(
    val initialized: Boolean,
    val screenId: String?,
    val placement: String?,
    val minimized: Boolean?,
    val boundsDp: String?,
    val widthDp: Float?,
    val heightDp: Float?,
)

/** A request and what the OS made of it, checked after it had time to land. */
@Immutable
data class Verification(
    val epochMillis: Long,
    val request: String,
    val outcome: Outcome,
    val detail: String,
)

enum class Outcome { Applied, Mismatch, NotApplicable }

@Immutable
data class SignalCounters(
    val moves: Int = 0,
    val resizes: Int = 0,
    val focusChanges: Int = 0,
    val minimizeChanges: Int = 0,
)

@Immutable
data class StateLabState(
    val sessionOpen: Boolean = false,
    val flags: WindowFlags = WindowFlags(),
    val monitors: List<MonitorRow> = emptyList(),
    val snapshot: WindowSnapshot? = null,
    val v2: V2Readback? = null,
    val counters: SignalCounters = SignalCounters(),
    val lastSignal: String? = null,
    val verifications: List<Verification> = emptyList(),
)

sealed interface StateLabIntent {
    data object Open : StateLabIntent

    data object Close : StateLabIntent

    data class SetFlags(
        val flags: WindowFlags,
    ) : StateLabIntent

    data class RequestPlacement(
        val placement: PlacementChoice,
    ) : StateLabIntent

    data class RequestMinimized(
        val minimized: Boolean,
    ) : StateLabIntent

    data class RequestPosition(
        val target: PositionTarget,
    ) : StateLabIntent

    data class RequestSize(
        val preset: SizePreset,
    ) : StateLabIntent

    data class RequestScreen(
        val monitorId: String,
    ) : StateLabIntent

    data object Focus : StateLabIntent

    data object Refresh : StateLabIntent

    /** The session window exists; carries its native handle. */
    class Attached(
        val window: TaoWindow,
    ) : StateLabIntent {
        override fun toString(): String = "Attached(handle=${window.handle})"
    }
}

sealed interface StateLabEvent {
    data class SessionChanged(
        val open: Boolean,
    ) : StateLabEvent

    data class FlagsChanged(
        val flags: WindowFlags,
    ) : StateLabEvent

    data class MonitorsRead(
        val monitors: List<MonitorRow>,
    ) : StateLabEvent {
        override fun toString(): String = "MonitorsRead(${monitors.joinToString { "${it.name} ${it.scale}x" }})"
    }

    data class Snapshot(
        val snapshot: WindowSnapshot,
    ) : StateLabEvent {
        override fun toString(): String = "Snapshot(${snapshot.describe()})"
    }

    data class V2Changed(
        val v2: V2Readback,
    ) : StateLabEvent

    data class Signal(
        val signal: WindowSignal,
    ) : StateLabEvent

    data class Verified(
        val verification: Verification,
    ) : StateLabEvent
}

object StateLabReducer : Reducer<StateLabState, StateLabEvent> {
    private const val VERIFICATIONS = 12

    override fun reduce(
        state: StateLabState,
        event: StateLabEvent,
    ): StateLabState =
        when (event) {
            is StateLabEvent.SessionChanged ->
                if (event.open) state.copy(sessionOpen = true) else state.copy(sessionOpen = false, snapshot = null)
            is StateLabEvent.FlagsChanged -> state.copy(flags = event.flags)
            is StateLabEvent.MonitorsRead -> state.copy(monitors = event.monitors)
            is StateLabEvent.Snapshot -> state.copy(snapshot = event.snapshot)
            is StateLabEvent.V2Changed -> state.copy(v2 = event.v2)
            is StateLabEvent.Signal ->
                state.copy(
                    counters = state.counters.count(event.signal),
                    lastSignal = event.signal.toString(),
                )
            is StateLabEvent.Verified ->
                state.copy(
                    verifications = state.verifications.pushFront(event.verification, VERIFICATIONS),
                )
        }

    private fun SignalCounters.count(signal: WindowSignal) =
        when (signal) {
            is WindowSignal.Moved -> copy(moves = moves + 1)
            is WindowSignal.Resized -> copy(resizes = resizes + 1)
            is WindowSignal.Focus -> copy(focusChanges = focusChanges + 1)
            is WindowSignal.Minimized -> copy(minimizeChanges = minimizeChanges + 1)
            WindowSignal.Destroyed -> this
        }
}

/** A request, remembered until it is checked against what the OS did. */
sealed interface PendingRequest {
    val label: String

    /** How long the OS gets before the check: fullscreen animates on macOS. */
    val settleMillis: Long get() = 700

    data class Placement(
        val placement: PlacementChoice,
    ) : PendingRequest {
        override val label: String get() = "placement $placement"
        override val settleMillis: Long get() = if (placement == PlacementChoice.Fullscreen) 1_500 else 800
    }

    data class Minimized(
        val minimized: Boolean,
    ) : PendingRequest {
        override val label: String get() = if (minimized) "minimize" else "restore"
    }

    data class Position(
        val target: PositionTarget,
    ) : PendingRequest {
        override val label: String get() = "position ${target.name}"
    }

    data class Size(
        val preset: SizePreset,
    ) : PendingRequest {
        override val label: String get() = "size ${preset.label} dp"
    }

    data class Screen(
        val monitor: MonitorRow,
    ) : PendingRequest {
        override val label: String get() = "screen ${monitor.name}"
    }
}

/** Pure: did the OS do what [request] asked, judging by [snapshot] and [v2]? */
fun verify(
    request: PendingRequest,
    snapshot: WindowSnapshot,
    v2: V2Readback?,
    now: Long,
): Verification {
    fun result(
        outcome: Outcome,
        detail: String,
    ) = Verification(now, request.label, outcome, detail)

    val placementBlocked = !snapshot.canPlaceOnScreen
    return when (request) {
        is PendingRequest.Placement -> {
            val ok =
                when (request.placement) {
                    PlacementChoice.Floating -> !snapshot.maximized && !snapshot.fullscreen
                    PlacementChoice.Maximized -> snapshot.maximized
                    PlacementChoice.Fullscreen -> snapshot.fullscreen
                }
            result(
                if (ok) Outcome.Applied else Outcome.Mismatch,
                "maximized=${snapshot.maximized} fullscreen=${snapshot.fullscreen}",
            )
        }
        is PendingRequest.Minimized ->
            result(
                if (snapshot.minimized ==
                    request.minimized
                ) {
                    Outcome.Applied
                } else {
                    Outcome.Mismatch
                },
                "minimized=${snapshot.minimized}",
            )
        is PendingRequest.Size -> {
            val w = v2?.widthDp
            val h = v2?.heightDp
            if (w == null || h == null) {
                result(Outcome.Mismatch, "v2 state reports no size")
            } else {
                val ok =
                    abs(w - request.preset.size.width.value) <= SIZE_TOLERANCE_DP &&
                        abs(h - request.preset.size.height.value) <= SIZE_TOLERANCE_DP
                val outer = snapshot.outerPx?.let { "${it.width}×${it.height} px" } ?: "?"
                result(
                    if (ok) Outcome.Applied else Outcome.Mismatch,
                    "v2 size ${w.fmt(0)}×${h.fmt(0)} dp, outer $outer",
                )
            }
        }
        is PendingRequest.Position -> {
            val outer = snapshot.outerPx
            val work = snapshot.workAreaPx
            when {
                placementBlocked -> result(Outcome.NotApplicable, "native Wayland: the compositor owns the position")
                outer == null || work == null -> result(Outcome.Mismatch, "no outer bounds / work area to compare")
                else -> {
                    val expected = expectedTopLeft(request.target.alignment, IntSize(outer.width, outer.height), work)
                    val dx = outer.left - expected.first
                    val dy = outer.top - expected.second
                    val ok = abs(dx) <= POSITION_TOLERANCE_PX && abs(dy) <= POSITION_TOLERANCE_PX
                    result(
                        if (ok) Outcome.Applied else Outcome.Mismatch,
                        "outer ${outer.left},${outer.top} vs aligned ${expected.first},${expected.second} (Δ $dx,$dy px)",
                    )
                }
            }
        }
        is PendingRequest.Screen ->
            when {
                placementBlocked -> result(Outcome.NotApplicable, "native Wayland: the compositor picks the output")
                else -> {
                    val onTarget = snapshot.monitor == request.monitor.name
                    val idMatches = v2?.screenId == null || v2.screenId == request.monitor.id
                    result(
                        if (onTarget &&
                            idMatches
                        ) {
                            Outcome.Applied
                        } else {
                            Outcome.Mismatch
                        },
                        "window on ${snapshot.monitor}, v2 screenId=${v2?.screenId}",
                    )
                }
            }
    }
}

/** Decorations and DPI rounding make a few dp of slack honest. */
private const val SIZE_TOLERANCE_DP = 2f

/**
 * Generous on purpose: v2 aligns the content size inside the work area while the outer
 * frame carries the decorations (and Windows' invisible resize borders), so a few dozen
 * px either way is the frame, not a bug.
 */
private const val POSITION_TOLERANCE_PX = 24
