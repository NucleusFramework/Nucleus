package dev.nucleusframework.lab.probes.rendering.textures

import androidx.compose.runtime.Immutable
import dev.nucleusframework.lab.core.Availability
import dev.nucleusframework.lab.core.mvi.Reducer

@Immutable
data class ProducerInfo(
    val role: String,
    val kind: String,
    val syncMode: String,
)

/** Which surface a GPU context was resolved against. */
enum class GpuSurface { Window, TrayPanel }

@Immutable
data class TexturesState(
    val producerAvailability: Availability = Availability.Unknown,
    val producers: List<ProducerInfo> = emptyList(),
    val animating: Boolean = true,
    val trayPanel: Boolean = false,
    val contexts: Map<GpuSurface, GpuContextInfo?> = emptyMap(),
) {
    /** On macOS/Linux a standalone panel owns a private Skia context; Windows shares one per owner. */
    val panelHasOwnContext: Boolean?
        get() {
            val window = contexts[GpuSurface.Window] ?: return null
            val panel = contexts[GpuSurface.TrayPanel] ?: return null
            return window.skiaContextId != panel.skiaContextId
        }
}

sealed interface TexturesIntent {
    data object ToggleAnimation : TexturesIntent

    data object ToggleTrayPanel : TexturesIntent

    data class ContextResolved(
        val surface: GpuSurface,
        val info: GpuContextInfo?,
    ) : TexturesIntent
}

sealed interface TexturesEvent {
    data class ProducersCreated(
        val producers: List<ProducerInfo>,
    ) : TexturesEvent

    data object AnimationToggled : TexturesEvent

    data object TrayPanelToggled : TexturesEvent

    data class ContextResolved(
        val surface: GpuSurface,
        val info: GpuContextInfo?,
    ) : TexturesEvent
}

object TexturesReducer : Reducer<TexturesState, TexturesEvent> {
    override fun reduce(
        state: TexturesState,
        event: TexturesEvent,
    ): TexturesState =
        when (event) {
            is TexturesEvent.ProducersCreated ->
                state.copy(
                    producers = event.producers,
                    producerAvailability =
                        Availability.of(event.producers.isNotEmpty()) {
                            "no producer: needs Windows + D3D11 (ANGLE), macOS + Metal, or Linux + a DRM render node"
                        },
                )
            TexturesEvent.AnimationToggled -> state.copy(animating = !state.animating)
            TexturesEvent.TrayPanelToggled -> state.copy(trayPanel = !state.trayPanel)
            is TexturesEvent.ContextResolved -> state.copy(contexts = state.contexts + (event.surface to event.info))
        }
}
