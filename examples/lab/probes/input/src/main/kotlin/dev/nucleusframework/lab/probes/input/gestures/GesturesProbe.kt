package dev.nucleusframework.lab.probes.input.gestures

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import dev.nucleusframework.lab.core.Check
import dev.nucleusframework.lab.core.Domain
import dev.nucleusframework.lab.core.Probe
import dev.nucleusframework.lab.core.ProbeDescriptor
import dev.nucleusframework.lab.core.ProbeId
import dev.nucleusframework.lab.core.format.fmt
import dev.nucleusframework.lab.core.time.monotonicMillis
import dev.nucleusframework.lab.designsystem.Actions
import dev.nucleusframework.lab.designsystem.ChoiceRow
import dev.nucleusframework.lab.designsystem.Counters
import dev.nucleusframework.lab.designsystem.LabTheme
import dev.nucleusframework.lab.designsystem.MonoTable
import dev.nucleusframework.lab.designsystem.ProbeLayout
import dev.nucleusframework.lab.designsystem.Readout
import dev.nucleusframework.lab.designsystem.SecondaryAction
import dev.nucleusframework.lab.designsystem.SubHeading
import dev.nucleusframework.lab.designsystem.TargetArea
import dev.nucleusframework.lab.designsystem.Text
import dev.nucleusframework.lab.designsystem.Tone
import dev.nucleusframework.lab.probes.input.common.fmt
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metrox.viewmodel.metroViewModel

@ContributesIntoSet(AppScope::class)
@Inject
class GesturesProbe : Probe {
    override val descriptor =
        ProbeDescriptor(
            id = ID,
            title = "Pinch, rotate & magnify",
            domain = Domain.Input,
            summary = "Do trackpad pinch and rotation reach Compose as the right events, through every consuming API?",
            modules = listOf("decorated-window-tao"),
            checks =
                listOf(
                    Check(
                        "pinch",
                        "A trackpad pinch (macOS) / touchpad pinch (GDK) / Ctrl+wheel (Windows) zooms in every mode; ScaleStart and ScaleEnd pair up",
                    ),
                    Check(
                        "rotate",
                        "Two-finger rotation turns the card in detectTransformGestures and transformable modes",
                    ),
                    Check(
                        "first-wins",
                        "A gesture that pinches then rotates stays the gesture it started as: 'Scale while touch down' stays 0 (#660)",
                    ),
                    Check(
                        "no-tap",
                        "Ending or interrupting a rotation (move the cursor, click) never clicks anything under the card",
                    ),
                    Check("finite", "Extreme pinches never produce 'Non-finite factors' and the card never vanishes"),
                    Check(
                        "drag",
                        "In the two transform modes, dragging the card with the mouse follows the cursor 1:1 at any zoom and rotation",
                    ),
                ),
            keywords = listOf("zoom", "magnify", "transformable", "touchpad", "trackpad"),
        )

    @Composable
    override fun Content() {
        val vm = metroViewModel<GesturesViewModel>()
        val state by vm.state.collectAsState()

        ProbeLayout(
            capabilities = emptyList(),
            controls = {
                ChoiceRow(
                    "Consumer",
                    GestureMode.entries,
                    state.mode,
                    name = { it.label },
                ) { vm.onIntent(GesturesIntent.SetMode(it)) }
                GestureCanvas(vm, state.mode, state.transform)
                Actions {
                    SecondaryAction("Reset transform") { vm.onIntent(GesturesIntent.ResetTransform) }
                    SecondaryAction("Reset counters") { vm.onIntent(GesturesIntent.ResetCounters) }
                }
            },
            observed = {
                with(state.transform) {
                    Readout(
                        "Transform",
                        "scale ${scale.fmt()} · rotation ${rotation.fmt(1)}° · offset ${offset.fmt(0)}",
                    )
                }
                Counters(
                    listOf(
                        "ScaleStart" to state.scaleStarts,
                        "ScaleChange" to state.scaleChanges,
                        "ScaleEnd" to state.scaleEnds,
                        "transform callbacks" to state.transformCallbacks,
                    ),
                )
                Readout("Touch contacts", "${state.touchContacts} now · ${state.maxTouchContacts} max")
                Readout(
                    "Scale while touch down",
                    "${state.overlaps}",
                    tone = if (state.overlaps > 0) Tone.Error else Tone.Ok,
                )
                Readout(
                    "Non-finite factors",
                    "${state.invalidFactors}",
                    tone = if (state.invalidFactors > 0) Tone.Error else Tone.Ok,
                )
                state.openPinch?.let {
                    Readout(
                        "Open pinch",
                        "${it.steps} steps · ×${it.factor.fmt(3)}",
                        tone = Tone.Warning,
                    )
                }
                SubHeading("Pinches (newest first)")
                MonoTable(
                    header = listOf("#", "steps", "× total", "ms"),
                    rows =
                        state.summaries.map {
                            listOf(
                                "${it.index}",
                                "${it.steps}",
                                it.factor.fmt(3),
                                "${it.durationMs}",
                            )
                        },
                    widths = listOf(32.dp, 56.dp, 80.dp, 56.dp),
                )
            },
        )
    }

    companion object {
        val ID = ProbeId("input.gestures")
    }
}

@Composable
private fun GestureCanvas(
    vm: GesturesViewModel,
    mode: GestureMode,
    transform: Transform,
) {
    val transformable = rememberTransformableState { zoom, pan, rotation -> vm.onTransform(zoom, rotation, pan) }
    val consumer =
        when (mode) {
            GestureMode.ScaleEvents -> Modifier
            GestureMode.DetectTransform ->
                Modifier.pointerInput(mode) {
                    detectTransformGestures { _, pan, zoom, rot -> vm.onTransform(zoom, rot, pan) }
                }
            GestureMode.Transformable -> Modifier.transformable(transformable)
        }
    TargetArea(
        Modifier
            .height(360.dp)
            // Observer on the Initial pass: counts what arrives whatever the consumer does.
            .pointerInput(vm) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val touches = event.changes.count { it.type == PointerType.Touch && it.pressed }
                        vm.onTouchContacts(touches)
                        when (event.type) {
                            PointerEventType.ScaleStart -> vm.onScaleStart(monotonicMillis(), touches)
                            PointerEventType.ScaleChange ->
                                vm.onScaleChange(event.changes.fold(1f) { f, c -> f * c.scaleFactor }, touches)
                            PointerEventType.ScaleEnd -> vm.onScaleEnd(monotonicMillis())
                            else -> Unit
                        }
                    }
                }
            }.then(consumer),
    ) {
        Box(
            Modifier
                .align(Alignment.Center)
                .size(200.dp)
                .graphicsLayer(
                    scaleX = transform.scale,
                    scaleY = transform.scale,
                    rotationZ = transform.rotation,
                    translationX = transform.offset.x,
                    translationY = transform.offset.y,
                ).clip(RoundedCornerShape(20.dp))
                .background(Brush.linearGradient(listOf(Color(0xFF8AB4FF), Color(0xFF34D399), Color(0xFF8B5CF6)))),
            contentAlignment = Alignment.Center,
        ) {
            Text("Pinch / Rotate / Drag", style = LabTheme.typography.heading, color = LabTheme.colors.onAccent)
        }
    }
}
