package dev.nucleusframework.lab.probes.rendering.partialredraw

import androidx.compose.runtime.Immutable
import dev.nucleusframework.lab.core.mvi.Reducer

/** What the damage sample animates; each one should damage a different share of the frame. */
enum class DamageScene(
    val label: String,
    val expectation: String,
) {
    Idle("Idle", "Nothing changes: no frame is presented at all, so tint shows nothing."),
    Blink("Blinking square", "Only the 48 dp square is repainted: tint flashes that square alone."),
    Sweep("Horizontal sweep", "A bar crosses one band: tint covers that band, never the whole window."),
    Counter("Text counter", "One line of text changes ten times a second: tint hugs the text line."),
    FullFrame("Full-frame colour", "The whole sample changes colour: a full repaint, which is expected here."),
}

@Immutable
data class PartialRedrawState(
    val config: PartialRedrawConfig? = null,
    val scene: DamageScene = DamageScene.Blink,
    val intervalMillis: Long = 500,
)

sealed interface PartialRedrawIntent {
    data object Refresh : PartialRedrawIntent

    data class SelectScene(
        val scene: DamageScene,
    ) : PartialRedrawIntent

    data class SetInterval(
        val millis: Long,
    ) : PartialRedrawIntent

    data object CopyRelaunchFlags : PartialRedrawIntent
}

sealed interface PartialRedrawEvent {
    data class ConfigRead(
        val config: PartialRedrawConfig,
    ) : PartialRedrawEvent

    data class SceneSelected(
        val scene: DamageScene,
    ) : PartialRedrawEvent

    data class IntervalChanged(
        val millis: Long,
    ) : PartialRedrawEvent
}

sealed interface PartialRedrawEffect {
    data class Copy(
        val text: String,
    ) : PartialRedrawEffect
}

object PartialRedrawReducer : Reducer<PartialRedrawState, PartialRedrawEvent> {
    const val MIN_INTERVAL = 50L
    const val MAX_INTERVAL = 2_000L

    override fun reduce(
        state: PartialRedrawState,
        event: PartialRedrawEvent,
    ): PartialRedrawState =
        when (event) {
            is PartialRedrawEvent.ConfigRead -> state.copy(config = event.config)
            is PartialRedrawEvent.SceneSelected -> state.copy(scene = event.scene)
            is PartialRedrawEvent.IntervalChanged ->
                state.copy(
                    intervalMillis = event.millis.coerceIn(MIN_INTERVAL, MAX_INTERVAL),
                )
        }
}

/** The flags that turn the inspection modes on, for a relaunch from a shell. */
fun relaunchFlags(): String =
    listOf(
        "-Dnucleus.tao.partialRedraw=true",
        "-Dnucleus.tao.partialRedraw.tint=true",
        "-Dnucleus.tao.partialRedraw.debug=true",
    ).joinToString(" ")
