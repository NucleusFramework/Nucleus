package dev.nucleusframework.scaffolddemo

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BrightnessAuto
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.nucleusframework.core.runtime.Platform
import dev.nucleusframework.window.DecoratedWindowScope
import dev.nucleusframework.window.LocalWindowChromeInsets
import dev.nucleusframework.window.WindowControls
import dev.nucleusframework.window.WindowGlassRegionKind
import dev.nucleusframework.window.noWindowDrag
import dev.nucleusframework.window.windowDragArea
import dev.nucleusframework.window.windowGlassRegion

internal val ToolbarHeight = 52.dp
private val SidebarWidth = 248.dp
private val SidebarCorner = 12.dp

/**
 * The window's chrome: a draggable strip carrying the title, the theme switch
 * and — everywhere the system does not draw them itself — the platform window
 * controls.
 */
@Composable
internal fun DecoratedWindowScope.DemoToolbar(demo: DemoState) {
    val colors = MaterialTheme.colorScheme
    val insets = LocalWindowChromeInsets.current
    val isMacOS = Platform.Current == Platform.MacOS

    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(ToolbarHeight)
                // Overlay means the bar owns no pixels: the content fills the
                // window and stays fully visible up to the top edge, with the
                // controls floating over it — otherwise Docked and Overlay
                // render identically and the placement switch shows nothing.
                // Under a Windows backdrop the strip is equally unpainted so
                // the material carries across the title bar. Docked keeps the
                // classic opaque band.
                .background(
                    if (demo.backdropActive || demo.overlay) Color.Transparent else colors.surface,
                )
                // The whole strip moves the window; the buttons inside opt out
                // by consuming the press.
                .windowDragArea(),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxHeight()
                    // Keep clear of the macOS traffic-lights, which float over
                    // the leading edge of the client area.
                    .padding(insets.controlsInsets)
                    .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = "Nucleus Window Lab",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = colors.onSurface,
            )
            Text(
                text = demo.section.title,
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
            )
        }

        Row(
            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ThemeModeButton(
                mode = demo.themeMode,
                resolvedDark = demo.darkTheme,
                onCycle = {
                    demo.themeMode =
                        when (demo.themeMode) {
                            ThemeMode.System -> ThemeMode.Light
                            ThemeMode.Light -> ThemeMode.Dark
                            ThemeMode.Dark -> ThemeMode.System
                        }
                },
                modifier = Modifier.padding(end = 10.dp),
            )
            // macOS draws real traffic-lights; every other platform is fully
            // undecorated, so the window would have no controls at all.
            if (!isMacOS) {
                WindowControls(Modifier.fillMaxHeight())
            }
        }
    }
}

@Composable
private fun ThemeModeButton(
    mode: ThemeMode,
    resolvedDark: Boolean,
    onCycle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    // The System state reads as "hands off": neutral chip, auto glyph. The
    // explicit overrides get the primary tint.
    val overridden = mode != ThemeMode.System
    val background by animateColorAsState(
        if (overridden) colors.primary.copy(alpha = 0.18f) else colors.onSurface.copy(alpha = 0.06f),
        label = "themeToggleBackground",
    )
    Box(
        modifier =
            modifier
                .size(32.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(background)
                .selectable(
                    selected = overridden,
                    role = Role.Button,
                    onClick = onCycle,
                ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector =
                when (mode) {
                    ThemeMode.System -> Icons.Rounded.BrightnessAuto
                    ThemeMode.Light -> Icons.Rounded.LightMode
                    ThemeMode.Dark -> Icons.Rounded.DarkMode
                },
            contentDescription =
                when (mode) {
                    ThemeMode.System ->
                        "Theme follows the system (currently ${if (resolvedDark) "dark" else "light"}); " +
                            "click for light"
                    ThemeMode.Light -> "Light theme forced; click for dark"
                    ThemeMode.Dark -> "Dark theme forced; click to follow the system"
                },
            tint = if (overridden) colors.primary else colors.onSurfaceVariant,
            modifier = Modifier.size(17.dp),
        )
    }
}

/**
 * Navigation pane. When the glass region is on it paints no background of its
 * own — that is what lets the system material show through it.
 */
@Composable
internal fun DemoSidebar(
    demo: DemoState,
    contentPadding: PaddingValues,
) {
    val colors = MaterialTheme.colorScheme
    // `windowGlassRegion` is a no-op off macOS, so the switch must be too:
    // branching on it alone left the pane with neither the material nor the
    // fallback fill everywhere else, which reads as a bug rather than a no-op.
    val glass = demo.glassSidebar && Platform.Current == Platform.MacOS
    // Under a Windows backdrop the pane is deliberately unpainted instead —
    // that is what lets the material run behind it.
    val paintsBackground = !glass && !demo.backdropActive

    // A floating panel, the way macOS lays its sidebars out: inset from the
    // window edges and rounded, so the material reads as its own surface
    // rather than as a flat colour flush against the frame.
    Column(
        modifier =
            Modifier
                .width(SidebarWidth)
                .fillMaxHeight()
                .padding(contentPadding)
                .padding(start = 9.dp, end = 3.dp, top = 6.dp, bottom = 9.dp)
                .clip(RoundedCornerShape(SidebarCorner))
                .then(
                    if (glass) {
                        Modifier.windowGlassRegion(
                            kind = WindowGlassRegionKind.Sidebar,
                            cornerRadius = SidebarCorner,
                        )
                    } else {
                        Modifier
                    },
                ).then(
                    if (paintsBackground) Modifier.background(colors.surfaceContainer) else Modifier,
                ).padding(horizontal = 8.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        DemoSection.entries.forEach { section ->
            SidebarItem(
                section = section,
                selected = demo.section == section,
                onClick = { demo.section = section },
            )
        }
        Spacer(Modifier.weight(1f))
        Text(
            text = if (glass) "Sidebar: system material" else "Sidebar: painted surface",
            style = MaterialTheme.typography.labelSmall,
            color = colors.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
        )
    }
}

@Composable
private fun SidebarItem(
    section: DemoSection,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val background by animateColorAsState(
        when {
            selected -> colors.primary.copy(alpha = 0.16f)
            else -> Color.Transparent
        },
        label = "sidebarItemBackground",
    )
    val contentColor = if (selected) colors.primary else colors.onSurface

    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(background)
                // A sidebar row is a control, not a place to grab the window:
                // opt out so a click-drag scrolls or selects instead of moving
                // it, whatever the surrounding chrome does.
                .noWindowDrag()
                .selectable(selected = selected, role = Role.Tab, onClick = onClick)
                .padding(horizontal = 10.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        Icon(
            imageVector = section.icon,
            contentDescription = null,
            tint = contentColor,
            modifier = Modifier.size(18.dp),
        )
        Column {
            Text(
                text = section.title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = contentColor,
            )
            Text(
                text = section.subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = colors.onSurfaceVariant,
            )
        }
    }
}
