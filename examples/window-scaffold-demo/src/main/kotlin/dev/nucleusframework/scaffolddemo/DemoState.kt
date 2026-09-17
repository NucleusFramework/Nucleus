package dev.nucleusframework.scaffolddemo

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BlurOn
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.Dashboard
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.OpenWith
import androidx.compose.material.icons.rounded.VerticalSplit
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import dev.nucleusframework.window.WindowsBackdropStyle
import dev.nucleusframework.window.WindowsBackdropTier

/** A page of the demo, one per chrome primitive. */
enum class DemoSection(
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
) {
    Overview(
        title = "Overview",
        subtitle = "What this window is made of",
        icon = Icons.Rounded.Dashboard,
    ),
    Placement(
        title = "Placement",
        subtitle = "Docked bar or content under it",
        icon = Icons.Rounded.VerticalSplit,
    ),
    Glass(
        title = "Glass region",
        subtitle = "System material behind a panel",
        icon = Icons.Rounded.BlurOn,
    ),
    Backdrop(
        title = "Windows backdrop",
        subtitle = "Mica and Acrylic behind the window",
        icon = Icons.Rounded.Layers,
    ),
    Appearance(
        title = "Appearance",
        subtitle = "Theme and native surfaces",
        icon = Icons.Rounded.DarkMode,
    ),
    Interaction(
        title = "Drag & controls",
        subtitle = "Move regions and window buttons",
        icon = Icons.Rounded.OpenWith,
    ),
}

/**
 * Tint handed to `WindowsBackdrop`. Only the Windows 10 acrylic fallback uses
 * it — the DWM materials colour themselves — and the alpha is what trades
 * prettiness against readability, so the choices below span that range.
 */
enum class BackdropTint(
    val label: String,
    val color: Color,
) {
    /** No tint passed: follows the window background at a legible opacity. */
    FollowTheme("Theme", Color.Unspecified),
    Slate("Slate", Color(0xCC1B2430)),
    Indigo("Indigo", Color(0xCC2A2A5A)),
    Sand("Sand", Color(0xCCE8DCC8)),

    /** Deliberately thin — shows how much blur a low alpha lets through. */
    Sheer("Sheer", Color(0x66202020)),
}

/** How the demo resolves its theme. */
enum class ThemeMode { System, Light, Dark }

/**
 * Everything the chrome reacts to, hoisted so the sidebar, the toolbar and the
 * pages all drive the same window.
 */
@Immutable
class DemoState(
    initialSystemInDark: Boolean,
) {
    var section by mutableStateOf(DemoSection.Overview)
    var overlay by mutableStateOf(true)
    var glassSidebar by mutableStateOf(true)

    /** Cycled by the title-bar button and set from the Appearance page. */
    var themeMode by mutableStateOf(ThemeMode.System)

    /** Live OS setting, kept in sync by `Main` from `isSystemInDarkMode()`. */
    var systemInDark by mutableStateOf(initialSystemInDark)

    /** Resolved theme every reader uses; [ThemeMode.System] follows the OS. */
    val darkTheme: Boolean
        get() =
            when (themeMode) {
                ThemeMode.System -> systemInDark
                ThemeMode.Light -> false
                ThemeMode.Dark -> true
            }
    var dragFromContent by mutableStateOf(false)
    var backdrop by mutableStateOf(WindowsBackdropStyle.Default)
    var backdropTint by mutableStateOf(BackdropTint.FollowTheme)
    var backdropTier by mutableStateOf(WindowsBackdropTier.Auto)

    /**
     * A backdrop is only visible where the app paints nothing, so the demo
     * drops its opaque surfaces while one is active.
     */
    val backdropActive: Boolean
        get() =
            backdrop == WindowsBackdropStyle.Mica ||
                backdrop == WindowsBackdropStyle.Acrylic ||
                backdrop == WindowsBackdropStyle.MicaAlt
}
