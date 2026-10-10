package dev.nucleusframework.lab.probes.input.scroll

import androidx.compose.ui.geometry.Offset
import androidx.lifecycle.ViewModel
import dev.nucleusframework.core.runtime.Platform
import dev.nucleusframework.lab.core.format.fmt
import dev.nucleusframework.lab.core.mvi.MviViewModel
import dev.nucleusframework.lab.core.timeline.Timeline
import dev.nucleusframework.lab.probes.input.common.fmt
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey

enum class ScrollKind { Scroll, PanStart, PanMove, PanEnd, ScaleStart, ScaleChange, ScaleEnd }

/** A root-level pointer event reduced to what the meter needs; built in the composable. */
data class ScrollSample(
    val kind: ScrollKind,
    val nowMs: Long,
    /** Wheel units for Scroll, pixels for PanMove. */
    val delta: Offset = Offset.Zero,
    val scaleFactor: Float = 1f,
    val scrollValuePx: Int,
    val density: Float,
)

/** Pixels of trackpad pan per wheel unit: Nucleus sizes `panOffset` like one `preciseWheelRotation` = 10 dp. */
const val PAN_DP_PER_WHEEL_UNIT = 10f

@ViewModelKey
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
@Inject
class ScrollViewModel(
    timeline: Timeline,
) : MviViewModel<ScrollState, ScrollIntent, ScrollEvent, ScrollEffect>(
        ScrollState(panEventsEnabled = System.getProperty("nucleus.tao.trackpadPanEvents", "true").toBoolean()),
        ScrollReducer,
        timeline,
        ScrollProbe.ID,
    ) {
    private val meter = ScrollMeter()
    private var lastEventMs = 0L

    /** Every frame of the frame clock; feeds the gesture fps. */
    fun onFrame() = meter.onFrame()

    fun onFps(fps: Int) = reduceSilently(ScrollEvent.Fps(fps))

    /** Ticker (≈25 Hz): scroll position readout and the idle close of wheel gestures. */
    fun onTick(
        nowMs: Long,
        scrollValuePx: Int,
        maxValuePx: Int,
    ) {
        reduceSilently(ScrollEvent.Position(scrollValuePx, maxValuePx))
        meter.onIdleCheck(nowMs, scrollValuePx)?.let { dispatch(ScrollEvent.GestureFinished(it)) }
    }

    fun onSample(sample: ScrollSample) {
        val gap = if (lastEventMs == 0L) 0L else sample.nowMs - lastEventMs
        lastEventMs = sample.nowMs
        val unitPx = PAN_DP_PER_WHEEL_UNIT * sample.density
        val c = state.value.counters
        var liveRawY = state.value.liveRawY
        val (counters, text) =
            when (sample.kind) {
                ScrollKind.Scroll -> {
                    meter.onDelta(sample.nowMs, sample.delta, sample.scrollValuePx)
                    liveRawY = sample.delta.y
                    c.copy(scroll = c.scroll + 1) to
                        "Scroll       Δ=${sample.delta.fmt(3)} units = ${(sample.delta * unitPx).fmt(1)} px"
                }
                ScrollKind.PanMove -> {
                    val units = sample.delta / unitPx
                    meter.onDelta(sample.nowMs, units, sample.scrollValuePx)
                    liveRawY = units.y
                    c.copy(panMove = c.panMove + 1) to
                        "PanMove      Δ=${sample.delta.fmt(1)} px = ${units.fmt(3)} units"
                }
                ScrollKind.PanStart, ScrollKind.PanEnd -> {
                    meter
                        .onBoundary(
                            sample.nowMs,
                            sample.scrollValuePx,
                        )?.let { dispatch(ScrollEvent.GestureFinished(it)) }
                    if (sample.kind == ScrollKind.PanStart) {
                        c.copy(panStart = c.panStart + 1) to "PanStart"
                    } else {
                        c.copy(panEnd = c.panEnd + 1) to "PanEnd       (+$gap ms after the previous event)"
                    }
                }
                ScrollKind.ScaleStart -> c.copy(scaleStart = c.scaleStart + 1) to "ScaleStart"
                ScrollKind.ScaleChange ->
                    c.copy(scaleChange = c.scaleChange + 1) to
                        "ScaleChange  ×${sample.scaleFactor.fmt(4)}"
                ScrollKind.ScaleEnd -> c.copy(scaleEnd = c.scaleEnd + 1) to "ScaleEnd"
            }
        reduceSilently(ScrollEvent.Observed(counters, liveRawY, "+%4d ms  %s".format(gap, text)))
    }

    override suspend fun handle(intent: ScrollIntent) {
        when (intent) {
            ScrollIntent.Reset -> {
                meter.reset()
                lastEventMs = 0L
                dispatch(ScrollEvent.Cleared)
            }
            ScrollIntent.CopyTsv ->
                emit(
                    ScrollEffect.Copy(
                        gesturesTsv(state.value.gestures, "${Platform.Current} ${System.getProperty("os.arch")}"),
                    ),
                )
        }
    }
}
