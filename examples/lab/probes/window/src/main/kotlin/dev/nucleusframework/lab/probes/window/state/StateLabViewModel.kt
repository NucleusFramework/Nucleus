package dev.nucleusframework.lab.probes.window.state

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPlacement
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.nucleusframework.lab.core.format.fmt
import dev.nucleusframework.lab.core.mvi.MviViewModel
import dev.nucleusframework.lab.core.mvi.map
import dev.nucleusframework.lab.core.session.SessionHost
import dev.nucleusframework.lab.core.timeline.Severity
import dev.nucleusframework.lab.core.timeline.Timeline
import dev.nucleusframework.lab.probes.window.shared.WindowFollow
import dev.nucleusframework.lab.probes.window.shared.WindowObserver
import dev.nucleusframework.lab.probes.window.shared.WindowSignal
import dev.nucleusframework.window.tao.TaoWindow
import dev.nucleusframework.window.tao.v2.WindowPositionProvider
import dev.nucleusframework.window.tao.v2.WindowScreenProvider
import dev.nucleusframework.window.tao.v2.WindowState
import dev.nucleusframework.window.tao.v2.WindowStateWithBounds
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.coroutines.delay

@ViewModelKey
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
@Inject
class StateLabViewModel(
    private val observer: WindowObserver,
    host: SessionHost,
    timeline: Timeline,
) : MviViewModel<StateLabState, StateLabIntent, StateLabEvent, Nothing>(
        StateLabState(),
        StateLabReducer,
        timeline,
        StateLabProbe.ID,
    ) {
    /**
     * The v2 state the session window is driven by. Owned here so requests come from the
     * probe and the readback outlives the window (closing and reopening keeps the geometry).
     */
    val windowState: WindowState = WindowStateWithBounds(initialSize = DpSize(720.dp, 480.dp))

    private val sessions = sessions(host)
    private val follow = WindowFollow(viewModelScope, observer)
    private var window: TaoWindow? = null

    init {
        dispatch(StateLabEvent.MonitorsRead(observer.monitors(null)))
        launch { sessions.isOpen().collect { dispatch(StateLabEvent.SessionChanged(it)) } }
        // Bounds change on every frame of a drag: mirror them, the settled snapshot is what gets logged.
        sessions.observe(::readV2) { reduceSilently(StateLabEvent.V2Changed(it)) }
    }

    override suspend fun handle(intent: StateLabIntent) {
        when (intent) {
            StateLabIntent.Open -> sessions.open("State lab") { close -> StateLabWindow(this@StateLabViewModel, close) }
            StateLabIntent.Close -> sessions.close()
            is StateLabIntent.SetFlags -> dispatch(StateLabEvent.FlagsChanged(intent.flags))
            is StateLabIntent.RequestPlacement -> {
                windowState.requestPlacement(
                    when (intent.placement) {
                        PlacementChoice.Floating -> WindowPlacement.Floating
                        PlacementChoice.Maximized -> WindowPlacement.Maximized
                        PlacementChoice.Fullscreen -> WindowPlacement.Fullscreen
                    },
                )
                verifyLater(PendingRequest.Placement(intent.placement))
            }
            is StateLabIntent.RequestMinimized -> {
                windowState.requestMinimized(intent.minimized)
                verifyLater(PendingRequest.Minimized(intent.minimized))
            }
            is StateLabIntent.RequestPosition -> {
                windowState.requestPosition(WindowPositionProvider.AlignedToScreen(intent.target.alignment))
                verifyLater(PendingRequest.Position(intent.target))
            }
            is StateLabIntent.RequestSize -> {
                windowState.requestSize(intent.preset.size)
                verifyLater(PendingRequest.Size(intent.preset))
            }
            is StateLabIntent.RequestScreen -> {
                val monitor = state.value.monitors.firstOrNull { it.id == intent.monitorId } ?: return
                windowState.requestScreen(WindowScreenProvider.ById(monitor.id))
                verifyLater(PendingRequest.Screen(monitor))
            }
            StateLabIntent.Focus -> window?.focus()
            StateLabIntent.Refresh -> {
                dispatch(StateLabEvent.MonitorsRead(observer.monitors(window)))
                window?.let { dispatch(StateLabEvent.Snapshot(observer.snapshot(it))) }
            }
            is StateLabIntent.Attached -> attach(intent.window)
        }
    }

    private fun attach(target: TaoWindow) {
        window = target
        dispatch(StateLabEvent.MonitorsRead(observer.monitors(target)))
        follow.follow(
            target,
            onSignal = { stamped ->
                // Geometry bursts only count; focus / minimize / destroy are worth a line each.
                val event = stamped.map(StateLabEvent::Signal)
                if (stamped.value.isGeometry) reduceSilently(event.value) else dispatch(event)
                if (stamped.value == WindowSignal.Destroyed && window === target) window = null
            },
            onSettled = { dispatch(StateLabEvent.Snapshot(it)) },
        )
    }

    private fun verifyLater(request: PendingRequest) {
        launch {
            delay(request.settleMillis)
            val target = window ?: return@launch
            val verification = verify(request, observer.snapshot(target), readV2(), System.currentTimeMillis())
            dispatch(
                StateLabEvent.Verified(verification),
                if (verification.outcome == Outcome.Mismatch) Severity.Warning else Severity.Info,
            )
        }
    }

    private fun readV2(): V2Readback =
        if (!windowState.isInitialized) {
            V2Readback(false, null, null, null, null, null, null)
        } else {
            val b = windowState.bounds
            V2Readback(
                initialized = true,
                screenId = windowState.screenId,
                placement = windowState.placement.name,
                minimized = windowState.isMinimized,
                boundsDp =
                    "${b.left.value.fmt(0)},${b.top.value.fmt(0)} " +
                        "${(b.right - b.left).value.fmt(0)}×${(b.bottom - b.top).value.fmt(0)}",
                widthDp = (b.right - b.left).value,
                heightDp = (b.bottom - b.top).value,
            )
        }
}
