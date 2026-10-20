package dev.nucleusframework.lab.probes.input.gestures

import androidx.compose.runtime.Immutable
import androidx.compose.ui.geometry.Offset
import dev.nucleusframework.lab.core.mvi.Reducer
import dev.nucleusframework.lab.core.mvi.pushFront

/** Which API drives the target: the three ways an app can consume a pinch. */
enum class GestureMode(
    val label: String,
) {
    ScaleEvents("Scale events"),
    DetectTransform("detectTransformGestures"),
    Transformable("Modifier.transformable"),
}

@Immutable
data class Transform(
    val scale: Float = 1f,
    val rotation: Float = 0f,
    val offset: Offset = Offset.Zero,
)

/** A closed pinch (ScaleStart → ScaleEnd) or transform burst. */
@Immutable
data class PinchSummary(
    val index: Int,
    val source: String,
    val steps: Int,
    val factor: Float,
    val rotation: Float,
    val durationMs: Long,
)

@Immutable
data class GesturesState(
    val mode: GestureMode = GestureMode.ScaleEvents,
    val transform: Transform = Transform(),
    val scaleStarts: Int = 0,
    val scaleChanges: Int = 0,
    val scaleEnds: Int = 0,
    val transformCallbacks: Int = 0,
    /** Touch contacts down right now (the synthetic rotation contacts on Tao). */
    val touchContacts: Int = 0,
    val maxTouchContacts: Int = 0,
    /** Scale events delivered while touch contacts were down: the two models must never overlap (#660). */
    val overlaps: Int = 0,
    /** Non-finite factors / zooms reaching the app. */
    val invalidFactors: Int = 0,
    val openPinch: OpenPinch? = null,
    val summaries: List<PinchSummary> = emptyList(),
    val nextIndex: Int = 1,
)

@Immutable
data class OpenPinch(
    val startMs: Long,
    val steps: Int = 0,
    val factor: Float = 1f,
)

sealed interface GesturesIntent {
    data class SetMode(
        val mode: GestureMode,
    ) : GesturesIntent

    data object ResetTransform : GesturesIntent

    data object ResetCounters : GesturesIntent
}

sealed interface GesturesEvent {
    data class ModeChanged(
        val mode: GestureMode,
    ) : GesturesEvent

    data class ScaleStarted(
        val nowMs: Long,
        val touchContacts: Int,
    ) : GesturesEvent

    data class ScaleChanged(
        val factor: Float,
        val touchContacts: Int,
        /** Applied to the target only in [GestureMode.ScaleEvents]. */
        val applies: Boolean,
    ) : GesturesEvent

    data class ScaleEnded(
        val nowMs: Long,
    ) : GesturesEvent

    data class Transformed(
        val zoom: Float,
        val rotation: Float,
        val pan: Offset,
    ) : GesturesEvent

    data class TouchContacts(
        val count: Int,
    ) : GesturesEvent

    data object TransformReset : GesturesEvent

    data object CountersReset : GesturesEvent
}

private const val MIN_SCALE = 0.25f
private const val MAX_SCALE = 6f
private const val SUMMARIES = 12

object GesturesReducer : Reducer<GesturesState, GesturesEvent> {
    override fun reduce(
        state: GesturesState,
        event: GesturesEvent,
    ): GesturesState =
        when (event) {
            is GesturesEvent.ModeChanged -> state.copy(mode = event.mode, transform = Transform())
            is GesturesEvent.ScaleStarted ->
                state
                    .copy(
                        scaleStarts = state.scaleStarts + 1,
                        openPinch = OpenPinch(event.nowMs),
                    ).overlapIf(event.touchContacts)
            is GesturesEvent.ScaleChanged -> {
                if (!event.factor.isFinite() || event.factor <= 0f) {
                    state.copy(scaleChanges = state.scaleChanges + 1, invalidFactors = state.invalidFactors + 1)
                } else {
                    val open = state.openPinch
                    state
                        .copy(
                            scaleChanges = state.scaleChanges + 1,
                            openPinch = open?.copy(steps = open.steps + 1, factor = open.factor * event.factor),
                            transform = if (event.applies) state.transform.zoomed(event.factor) else state.transform,
                        ).overlapIf(event.touchContacts)
                }
            }
            is GesturesEvent.ScaleEnded -> {
                val open = state.openPinch
                val closed = state.copy(scaleEnds = state.scaleEnds + 1, openPinch = null)
                if (open == null) {
                    closed
                } else {
                    closed.summarize(
                        PinchSummary(
                            state.nextIndex,
                            "Scale",
                            open.steps,
                            open.factor,
                            0f,
                            event.nowMs - open.startMs,
                        ),
                    )
                }
            }
            is GesturesEvent.Transformed ->
                if (!event.zoom.isFinite() || !event.rotation.isFinite()) {
                    state.copy(
                        transformCallbacks = state.transformCallbacks + 1,
                        invalidFactors =
                            state.invalidFactors + 1,
                    )
                } else {
                    val t = state.transform.zoomed(event.zoom)
                    state.copy(
                        transformCallbacks = state.transformCallbacks + 1,
                        transform = t.copy(rotation = t.rotation + event.rotation, offset = t.offset + event.pan),
                    )
                }
            is GesturesEvent.TouchContacts ->
                state.copy(
                    touchContacts = event.count,
                    maxTouchContacts = maxOf(state.maxTouchContacts, event.count),
                )
            GesturesEvent.TransformReset -> state.copy(transform = Transform())
            GesturesEvent.CountersReset -> GesturesState(mode = state.mode, transform = state.transform)
        }

    private fun Transform.zoomed(factor: Float) = copy(scale = (scale * factor).coerceIn(MIN_SCALE, MAX_SCALE))

    private fun GesturesState.overlapIf(touchContacts: Int) =
        if (touchContacts >
            0
        ) {
            copy(overlaps = overlaps + 1)
        } else {
            this
        }

    private fun GesturesState.summarize(summary: PinchSummary) =
        copy(summaries = summaries.pushFront(summary, SUMMARIES), nextIndex = nextIndex + 1)
}
