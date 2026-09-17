package dev.nucleusframework.scaffolddemo

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.nucleusframework.core.runtime.Platform
import dev.nucleusframework.window.WindowsBackdropStyle
import dev.nucleusframework.window.WindowsBackdropTier
import dev.nucleusframework.window.windowDragArea

/** The page for the selected section, plus its live switches. */
@Composable
internal fun DemoContent(
    demo: DemoState,
    contentPadding: PaddingValues,
) {
    val section = demo.section
    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding =
            PaddingValues(
                start = 28.dp,
                end = 28.dp,
                top = contentPadding.calculateTopPadding() + 26.dp,
                bottom = 32.dp,
            ),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            PageHeader(section)
            Spacer(Modifier.height(6.dp))
        }
        when (section) {
            DemoSection.Overview -> overviewPage(demo)
            DemoSection.Placement -> placementPage(demo)
            DemoSection.Glass -> glassPage(demo)
            DemoSection.Backdrop -> backdropPage(demo)
            DemoSection.Appearance -> appearancePage(demo)
            DemoSection.Interaction -> interactionPage(demo)
        }
    }
}

@Composable
private fun PageHeader(section: DemoSection) {
    val colors = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(
            modifier =
                Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(colors.primary.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(section.icon, contentDescription = null, tint = colors.primary, modifier = Modifier.size(21.dp))
        }
        Column {
            Text(
                text = section.title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                color = colors.onSurface,
            )
            Text(
                text = section.subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant,
            )
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.overviewPage(demo: DemoState) {
    item {
        InfoCard(
            title = "One window, assembled from primitives",
            body =
                "This window has no system title bar. `WindowScaffold` hosts the toolbar above, " +
                    "the sidebar draws Apple's system material through the window, the toolbar is a " +
                    "drag region, and the minimise / maximise / close buttons come from Nucleus on " +
                    "the platforms that need them. Every switch on these pages changes the real " +
                    "window, live.",
        )
    }
    item {
        SwitchRow(
            title = "Content under the title bar",
            description =
                "TitleBarPlacement.Overlay — the content fills the window height and the toolbar " +
                    "paints no band: the controls float over it.",
            checked = demo.overlay,
            onCheckedChange = { demo.overlay = it },
        )
    }
    item {
        SwitchRow(
            title = "Glass sidebar",
            description = "Modifier.windowGlassRegion — the desktop wallpaper shows through the pane.",
            checked = demo.glassSidebar,
            onCheckedChange = { demo.glassSidebar = it },
        )
    }
    item {
        PlatformNote()
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.placementPage(demo: DemoState) {
    item {
        InfoCard(
            title = "Docked or overlay",
            body =
                "Docked lays the bar out above the content, the classic decorated window. Overlay " +
                    "lets the content fill the whole window and floats the bar over it, handing the " +
                    "measured bar height back as content padding — that is what this page's list is " +
                    "inset by. The height is also published to the native layer, which centres the " +
                    "macOS traffic-lights against it and sizes the Windows caption zone.",
        )
    }
    item {
        SwitchRow(
            title = "Overlay placement",
            description = if (demo.overlay) "Content runs under the bar." else "Content starts below the bar.",
            checked = demo.overlay,
            onCheckedChange = { demo.overlay = it },
        )
    }
    item {
        HintCard("Scroll this list: in overlay mode the rows pass beneath the toolbar.")
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.glassPage(demo: DemoState) {
    item {
        InfoCard(
            title = "The System Settings sidebar, for real",
            body =
                "`Modifier.windowGlassRegion` hosts a genuine NSSplitViewController pane below the " +
                    "Compose surface, so AppKit applies the same desktop-tinted material as its own " +
                    "apps: the wallpaper shows through, windows behind never do, and light/dark, " +
                    "inactive-window desaturation and Reduce Transparency all behave natively. " +
                    "macOS only — elsewhere the pane simply paints a surface colour.",
        )
    }
    item {
        SwitchRow(
            title = "Glass sidebar",
            description = "Move the window over a colourful wallpaper to see it clearly.",
            checked = demo.glassSidebar,
            onCheckedChange = { demo.glassSidebar = it },
        )
    }
    item {
        HintCard(
            "Only the pane is a material. Apple keeps glass for that functional layer, never for a " +
                "whole window, so Nucleus exposes the region and nothing else.",
        )
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.backdropPage(demo: DemoState) {
    item {
        InfoCard(
            title = "One API per platform, named for it",
            body =
                "`WindowsBackdrop` sets DWMWA_SYSTEMBACKDROP_TYPE: DWM composites the material behind " +
                    "the window and the client area is left transparent, so it shows wherever the app " +
                    "paints nothing. Mica tints from the wallpaper for main windows, Acrylic blurs " +
                    "what is actually behind, Mica Alt is the stronger tint for tabbed windows. " +
                    "macOS has a per-region material instead (`Modifier.windowGlassRegion`) and Linux " +
                    "has no equivalent, so there is no cross-platform name for this.",
        )
    }
    item {
        BackdropPicker(demo)
    }
    item {
        BackdropTierPicker(demo)
    }
    item {
        BackdropTintPicker(demo)
    }
    item {
        HintCard(
            if (Platform.Current == Platform.Windows) {
                "Windows 11 22H2+ only — the DWM attribute does not exist before that, and the app " +
                    "silently keeps its opaque background. Move the window over a colourful " +
                    "wallpaper for Mica, or over a video for Acrylic."
            } else {
                "A no-op on this platform. The switches still move; the window will not."
            },
        )
    }
    item {
        HintCard(
            "The demo drops its opaque content surface while a backdrop is on — an app that paints " +
                "a full-bleed background would cover the material completely.",
        )
    }
    item {
        HintCard(
            "The caption buttons sit directly on the material, like Windows 11's own — no backing " +
                "plate. Their glyphs follow the app theme (toggle it on the Appearance page), so " +
                "they stay readable on the themed material.",
        )
    }
}

@Composable
private fun BackdropPicker(demo: DemoState) {
    val colors = MaterialTheme.colorScheme
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = colors.surfaceContainer),
    ) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                text = "Backdrop style",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = colors.onSurface,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                WindowsBackdropStyle.entries.forEach { style ->
                    FilterChip(
                        selected = demo.backdrop == style,
                        onClick = { demo.backdrop = style },
                        label = { Text(style.name) },
                    )
                }
            }
        }
    }
}

@Composable
private fun BackdropTierPicker(demo: DemoState) {
    val colors = MaterialTheme.colorScheme
    val labels =
        mapOf(
            WindowsBackdropTier.Auto to "Auto",
            WindowsBackdropTier.Modern to "Win11 22H2+",
            WindowsBackdropTier.LegacyMica to "Win11 < 22H2",
            WindowsBackdropTier.Windows10Acrylic to "Windows 10",
        )
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = colors.surfaceContainer),
    ) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                text = "Implementation tier",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = colors.onSurface,
            )
            Text(
                text =
                    "Only one tier ever runs on a given machine, which is how the other two get " +
                        "shipped broken. Pin one to see what an older Windows would show — " +
                        "\"Windows 10\" swaps Mica for the acrylic blur, and the tint below starts " +
                        "mattering. Apps ship Auto.",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                WindowsBackdropTier.entries.forEach { tier ->
                    FilterChip(
                        selected = demo.backdropTier == tier,
                        onClick = { demo.backdropTier = tier },
                        label = { Text(labels.getValue(tier)) },
                    )
                }
            }
        }
    }
}

@Composable
private fun BackdropTintPicker(demo: DemoState) {
    val colors = MaterialTheme.colorScheme
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = colors.surfaceContainer),
    ) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                text = "Acrylic tint",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = colors.onSurface,
            )
            Text(
                text =
                    "The app's tint layer over the material — Fluent acrylic is blur + tint + " +
                        "noise, and the tint belongs to the app. Unset, Acrylic follows the window " +
                        "background (DWM's own acrylic tint is a generic system grey, foreign to " +
                        "any themed app) while Mica needs none. The alpha trades prettiness " +
                        "against readability.",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BackdropTint.entries.forEach { tint ->
                    FilterChip(
                        selected = demo.backdropTint == tint,
                        onClick = { demo.backdropTint = tint },
                        label = { Text(tint.label) },
                        leadingIcon =
                            if (tint.color.isSpecified) {
                                {
                                    Box(
                                        Modifier
                                            .size(14.dp)
                                            .clip(RoundedCornerShape(4.dp))
                                            .background(tint.color),
                                    )
                                }
                            } else {
                                null
                            },
                    )
                }
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.appearancePage(demo: DemoState) {
    item {
        InfoCard(
            title = "Native surfaces follow the app",
            body =
                "An app that picks its own theme has to tell the window, or the OS keeps deciding: " +
                    "a dark app on a light system would get light traffic-lights and a light sidebar " +
                    "material under dark content. `WindowAppearance` forces the window's NSAppearance " +
                    "on macOS and the caption-button glyphs on Windows, reverting when it leaves the " +
                    "composition. Without it the Windows chrome follows the window background's " +
                    "luminance — the same signal that themes the Mica material — so the two can " +
                    "never disagree.",
        )
    }
    item {
        ThemeModePicker(demo)
    }
}

@Composable
private fun ThemeModePicker(demo: DemoState) {
    val colors = MaterialTheme.colorScheme
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = colors.surfaceContainer),
    ) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                text = "Theme",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = colors.onSurface,
            )
            Text(
                text =
                    "System follows the OS live — also cycled by the title-bar button. Watch the " +
                        "native surfaces track it: traffic-lights and sidebar material on macOS, " +
                        "caption-button glyphs and the backdrop material on Windows.",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ThemeMode.entries.forEach { mode ->
                    FilterChip(
                        selected = demo.themeMode == mode,
                        onClick = { demo.themeMode = mode },
                        label = { Text(mode.name) },
                    )
                }
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.interactionPage(demo: DemoState) {
    item {
        InfoCard(
            title = "Dragging, and opting out",
            body =
                "`Modifier.windowDragArea` turns any component into a window move region, with " +
                    "double-click to maximise; interactive children opt out by consuming the press. " +
                    "Detectors that only claim the pointer once it moves — scrollbars, sliders — need " +
                    "`Modifier.noWindowDrag` instead, which every sidebar row here uses.",
        )
    }
    item {
        SwitchRow(
            title = "Make this panel draggable",
            description = "Adds windowDragArea to the strip below — drag it to move the window.",
            checked = demo.dragFromContent,
            onCheckedChange = { demo.dragFromContent = it },
        )
    }
    item {
        DragSandbox(demo)
    }
    item {
        InfoCard(
            title = "Window controls",
            body =
                "`WindowControls` renders the platform's own buttons — Fluent on Windows, Adwaita or " +
                    "Breeze on Linux, following the desktop's button-layout for order and side — while " +
                    "Nucleus keeps the semantics. On macOS it draws nothing and reserves the " +
                    "traffic-light footprint instead, which is the inset this toolbar pads itself by. " +
                    "On Windows 11 the buttons speak the native caption protocol too: hover the " +
                    "maximize button to get the system Snap Layouts flyout.",
        )
    }
}

@Composable
private fun DragSandbox(demo: DemoState) {
    val colors = MaterialTheme.colorScheme
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(76.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(
                    if (demo.dragFromContent) {
                        colors.primary.copy(alpha = 0.14f)
                    } else {
                        colors.surfaceContainerHigh
                    },
                ).then(if (demo.dragFromContent) Modifier.windowDragArea() else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text =
                if (demo.dragFromContent) {
                    "Drag me — the window follows. Double-click to maximise."
                } else {
                    "Inert panel. Turn the switch on to make it a drag region."
                },
            style = MaterialTheme.typography.bodyMedium,
            color = if (demo.dragFromContent) colors.primary else colors.onSurfaceVariant,
        )
    }
}

@Composable
private fun PlatformNote() {
    val platform =
        when (Platform.Current) {
            Platform.MacOS -> "macOS — native traffic-lights, glass regions and backdrop are live here."
            Platform.Windows ->
                "Windows — Fluent caption buttons with native Snap Layouts, and Mica/Acrylic " +
                    "backdrops; the glass region is a no-op."
            else -> "Linux — Adwaita/Breeze buttons follow the desktop's button-layout; glass is a no-op."
        }
    HintCard(platform)
}

@Composable
private fun InfoCard(
    title: String,
    body: String,
) {
    val colors = MaterialTheme.colorScheme
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = colors.surfaceContainer),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = colors.onSurface,
            )
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SwitchRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = colors.surfaceContainer),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = colors.onSurface,
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                )
            }
            Spacer(Modifier.size(16.dp))
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        }
    }
}

@Composable
private fun HintCard(text: String) {
    val colors = MaterialTheme.colorScheme
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(colors.primary.copy(alpha = 0.09f))
                .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = colors.onSurfaceVariant,
        )
    }
}
