package dev.nucleusframework.lab.probes.window.chrome

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import dev.nucleusframework.lab.core.mvi.Reducer
import dev.nucleusframework.lab.probes.window.shared.WindowSignal
import dev.nucleusframework.lab.probes.window.shared.WindowSnapshot
import dev.nucleusframework.window.ControlButtonsDirection
import dev.nucleusframework.window.WindowsBackdropStyle
import dev.nucleusframework.window.WindowsBackdropTier
import dev.nucleusframework.window.tao.TaoWindow

enum class BarPlacement { Docked, Overlay }

/** Who draws the bar: the app's own toolbar, or the stock title bar (`JewelTitleBar`). */
enum class BarKind { Custom, Stock }

enum class AppearanceChoice { System, Light, Dark }

/** Tint handed to `WindowsBackdrop`; its alpha trades the material against readability. */
enum class TintChoice(
    val label: String,
    val color: Color,
) {
    FollowTheme("Theme", Color.Unspecified),
    Slate("Slate", Color(0xCC1B2430)),
    Sand("Sand", Color(0xCCE8DCC8)),
    Sheer("Sheer", Color(0x66202020)),
}

/** Everything the chrome window reacts to, live. */
@Immutable
data class ChromeConfig(
    val placement: BarPlacement = BarPlacement.Overlay,
    val autoHideInFullscreen: Boolean = true,
    val passThroughToContent: Boolean = false,
    val bar: BarKind = BarKind.Custom,
    val fillCenter: Boolean = false,
    val controlsDirection: ControlButtonsDirection = ControlButtonsDirection.Auto,
    val newFullscreenControls: Boolean = true,
    val appearance: AppearanceChoice = AppearanceChoice.System,
    val backdrop: WindowsBackdropStyle = WindowsBackdropStyle.Default,
    val tint: TintChoice = TintChoice.FollowTheme,
    val tier: WindowsBackdropTier = WindowsBackdropTier.Auto,
    val largeCorner: Boolean = true,
    val glassSidebar: Boolean = true,
    val dragFromContent: Boolean = false,
) {
    /** A backdrop only shows where nothing is painted, so the window stops painting while one is on. */
    val backdropActive: Boolean
        get() =
            backdrop == WindowsBackdropStyle.Mica ||
                backdrop == WindowsBackdropStyle.Acrylic ||
                backdrop == WindowsBackdropStyle.MicaAlt
}

/** What `WindowScaffold` published to the content, read inside the window. */
@Immutable
data class ChromeReadback(
    val titleBarHeightDp: Float,
    val controlsInsets: String,
    val layoutDirection: String,
)

@Immutable
data class ChromeState(
    val sessionOpen: Boolean = false,
    val config: ChromeConfig = ChromeConfig(),
    val readback: ChromeReadback? = null,
    val snapshot: WindowSnapshot? = null,
    val contentDragPresses: Int = 0,
)

sealed interface ChromeIntent {
    data object Open : ChromeIntent

    data object Close : ChromeIntent

    data class SetConfig(
        val config: ChromeConfig,
    ) : ChromeIntent

    data class Measured(
        val readback: ChromeReadback,
    ) : ChromeIntent

    /** A press landed on the content drag area (the window should start moving). */
    data object ContentDragPressed : ChromeIntent

    class Attached(
        val window: TaoWindow,
    ) : ChromeIntent {
        override fun toString(): String = "Attached(handle=${window.handle})"
    }
}

sealed interface ChromeEvent {
    data class SessionChanged(
        val open: Boolean,
    ) : ChromeEvent

    data class ConfigChanged(
        val config: ChromeConfig,
    ) : ChromeEvent

    data class Measured(
        val readback: ChromeReadback,
    ) : ChromeEvent

    data class Snapshot(
        val snapshot: WindowSnapshot,
    ) : ChromeEvent {
        override fun toString(): String = "Snapshot(${snapshot.describe()})"
    }

    data object ContentDragPressed : ChromeEvent

    /** A native focus / minimize / destroy notification: recorded with its thread, no state of its own. */
    data class Signal(
        val signal: WindowSignal,
    ) : ChromeEvent
}

object ChromeReducer : Reducer<ChromeState, ChromeEvent> {
    override fun reduce(
        state: ChromeState,
        event: ChromeEvent,
    ): ChromeState =
        when (event) {
            is ChromeEvent.SessionChanged ->
                if (event.open) {
                    state.copy(sessionOpen = true)
                } else {
                    state.copy(sessionOpen = false, readback = null, snapshot = null)
                }
            is ChromeEvent.ConfigChanged -> state.copy(config = event.config)
            is ChromeEvent.Measured -> state.copy(readback = event.readback)
            is ChromeEvent.Snapshot -> state.copy(snapshot = event.snapshot)
            ChromeEvent.ContentDragPressed -> state.copy(contentDragPresses = state.contentDragPresses + 1)
            is ChromeEvent.Signal -> state
        }
}
