package dev.nucleusframework.lab.probes.window.theming

import androidx.compose.runtime.Immutable
import dev.nucleusframework.lab.core.mvi.Reducer

enum class DesignSystem(
    val label: String,
    val module: String,
) {
    Material2("Material 2", "decorated-window-material2"),
    Material3("Material 3", "decorated-window-material3"),
    Jewel("Jewel (IntelliJ)", "decorated-window-jewel"),
}

enum class ThemeChoice { System, Light, Dark }

@Immutable
data class ThemedConfig(
    val theme: ThemeChoice = ThemeChoice.System,
    /** Paints the bar from the design system's accent: `gradientStartColor`. */
    val gradient: Boolean = false,
)

/** The styles the window actually resolved, read inside it. */
@Immutable
data class StyleReport(
    val isDark: Boolean,
    val titleBarBackground: String,
    val titleBarInactiveBackground: String,
    val titleBarContent: String,
    val windowBorder: String,
    val windowBackground: String,
)

@Immutable
data class ThemingState(
    val open: Set<DesignSystem> = emptySet(),
    val configs: Map<DesignSystem, ThemedConfig> = DesignSystem.entries.associateWith { ThemedConfig() },
    val reports: Map<DesignSystem, StyleReport> = emptyMap(),
) {
    fun config(system: DesignSystem): ThemedConfig = configs.getValue(system)
}

sealed interface ThemingIntent {
    data class Open(
        val system: DesignSystem,
    ) : ThemingIntent

    data class Close(
        val system: DesignSystem,
    ) : ThemingIntent

    data class Configure(
        val system: DesignSystem,
        val config: ThemedConfig,
    ) : ThemingIntent

    data class Reported(
        val system: DesignSystem,
        val report: StyleReport,
    ) : ThemingIntent
}

sealed interface ThemingEvent {
    data class OpenChanged(
        val system: DesignSystem,
        val open: Boolean,
    ) : ThemingEvent

    data class Configured(
        val system: DesignSystem,
        val config: ThemedConfig,
    ) : ThemingEvent

    data class Reported(
        val system: DesignSystem,
        val report: StyleReport,
    ) : ThemingEvent
}

object ThemingReducer : Reducer<ThemingState, ThemingEvent> {
    override fun reduce(
        state: ThemingState,
        event: ThemingEvent,
    ): ThemingState =
        when (event) {
            is ThemingEvent.OpenChanged ->
                if (event.open) {
                    state.copy(open = state.open + event.system)
                } else {
                    state.copy(open = state.open - event.system, reports = state.reports - event.system)
                }
            is ThemingEvent.Configured -> state.copy(configs = state.configs + (event.system to event.config))
            is ThemingEvent.Reported -> state.copy(reports = state.reports + (event.system to event.report))
        }
}

/** Pure: does the resolved style agree with the requested theme? `null` for System. */
fun StyleReport.matches(choice: ThemeChoice): Boolean? =
    when (choice) {
        ThemeChoice.System -> null
        ThemeChoice.Light -> !isDark
        ThemeChoice.Dark -> isDark
    }
