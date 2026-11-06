package dev.nucleusframework.lab.probes.window.popups

import androidx.compose.ui.unit.IntSize
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.nucleusframework.lab.core.mvi.MviViewModel
import dev.nucleusframework.lab.core.session.SessionHost
import dev.nucleusframework.lab.core.timeline.EntryKind
import dev.nucleusframework.lab.core.timeline.Severity
import dev.nucleusframework.lab.core.timeline.Timeline
import dev.nucleusframework.lab.probes.window.shared.WindowFollow
import dev.nucleusframework.lab.probes.window.shared.WindowObserver
import dev.nucleusframework.lab.probes.window.shared.expectedTopLeft
import dev.nucleusframework.lab.probes.window.state.PositionTarget
import dev.nucleusframework.window.tao.TaoWindow
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.coroutines.delay
import kotlin.math.abs

@ViewModelKey
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
@Inject
class PopupsViewModel(
    private val observer: WindowObserver,
    host: SessionHost,
    timeline: Timeline,
) : MviViewModel<PopupsState, PopupsIntent, PopupsEvent, Nothing>(
        PopupsState(),
        PopupsReducer,
        timeline,
        PopupsProbe.ID,
    ) {
    private val sessions = sessions(host)
    private val follow = WindowFollow(viewModelScope, observer)
    private var window: TaoWindow? = null

    init {
        launch { sessions.isOpen().collect { dispatch(PopupsEvent.SessionChanged(it)) } }
    }

    override suspend fun handle(intent: PopupsIntent) {
        when (intent) {
            PopupsIntent.Open -> sessions.open("Popups lab") { close -> PopupsWindow(this@PopupsViewModel, close) }
            PopupsIntent.Close -> sessions.close()
            is PopupsIntent.SetConfig -> dispatch(PopupsEvent.ConfigChanged(intent.config))
            is PopupsIntent.Park -> park(intent.target)
            is PopupsIntent.Measured -> {
                if (state.value.popups[intent.report.label] == intent.report) return
                val crosses = intent.report.fitsWorkArea == false
                dispatch(PopupsEvent.Measured(intent.report), if (crosses) Severity.Warning else Severity.Info)
            }
            is PopupsIntent.Picked -> dispatch(PopupsEvent.Picked(intent.label))
            is PopupsIntent.Attached -> attach(intent.window)
        }
    }

    /** The owner's monitor work area, for judging where a popup landed. */
    fun workArea() = state.value.snapshot?.workAreaPx

    /** Moves the window flush against [target] of its work area, the way #569 was reproduced. */
    private suspend fun park(target: PositionTarget) {
        val w = window ?: return
        val snapshot = observer.snapshot(w)
        val outer = snapshot.outerPx
        val work = snapshot.workAreaPx
        if (!snapshot.canPlaceOnScreen || outer == null || work == null) {
            timeline.record(
                source,
                EntryKind.Log,
                "Cannot park: ${if (!snapshot.canPlaceOnScreen) "native Wayland places windows itself" else "no geometry"}",
                Severity.Warning,
            )
            return
        }
        val (x, y) = expectedTopLeft(target.alignment, IntSize(outer.width, outer.height), work)
        // setOuterPosition takes logical units, as an app would.
        w.setOuterPosition(x / snapshot.scale.toDouble(), y / snapshot.scale.toDouble())
        delay(PARK_SETTLE_MS)
        val after = observer.snapshot(w).outerPx
        val message = "Parked ${target.name}: asked $x,$y px, got ${after?.left},${after?.top}"
        val off = after == null || abs(after.left - x) > 2 || abs(after.top - y) > 2
        timeline.record(source, EntryKind.Event, message, if (off) Severity.Warning else Severity.Info)
    }

    private fun attach(target: TaoWindow) {
        window = target
        follow.follow(target, onSettled = { reduceSilently(PopupsEvent.Snapshot(it)) })
    }

    private companion object {
        const val PARK_SETTLE_MS = 400L
    }
}
