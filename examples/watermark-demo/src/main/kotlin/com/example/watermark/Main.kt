package com.example.watermark

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState
import dev.nucleusframework.application.DecoratedWindow
import dev.nucleusframework.application.nucleusApplication
import dev.nucleusframework.composenativetray.tray.api.Tray
import dev.nucleusframework.core.runtime.Platform
import dev.nucleusframework.window.TitleBar
import dev.nucleusframework.window.material.MaterialDecoratedWindow
import dev.nucleusframework.window.tao.TaoWindow
import java.io.File

/**
 * Watermark overlay demo.
 *
 * Showcases full-window per-pixel transparency (#416) on the backend-agnostic
 * `DecoratedWindow`: a borderless, always-on-top, taskbar-hidden window pinned
 * to a screen corner, whose only visible pixels are an animated "NUCLEUS"
 * watermark pill — everything else shows the desktop through.
 *
 * The app has no Dock/taskbar presence (`hiddenFromDock`), never takes focus
 * (`focusable = false`), lets every click fall through to whatever sits
 * underneath (`clickThrough`) and stays on screen across desktops /
 * macOS Spaces (`visibleOnAllWorkspaces`); it is controlled entirely from its
 * tray icon (ComposeNativeTray): pick the corner the watermark is pinned to,
 * or quit.
 * Corner changes go through `WindowState.position = WindowPosition.Aligned(...)`,
 * which the Tao backend resolves against the primary monitor's work area at
 * runtime.
 *
 * A second, ordinary window drives it: toggle the watermark on and off, pick
 * its corner. That window keeps the session's native surface, which on Linux
 * makes the demo the mixed case — a Wayland app with one X11 window.
 *
 * **Linux**: an overlay like this needs X11 semantics — stacking
 * (`alwaysOnTop`), programmatic positioning (`WindowPosition.Aligned`) and
 * workspace stickiness are all absent from the Wayland protocol. Rather than
 * pushing the whole app onto XWayland with
 * `NUCLEUS_TAO_LINUX_RENDERER=x11`, the watermark asks for an X11 surface of
 * its own (`forceX11`); the control window stays on Wayland.
 *
 * The tray icon is fed as image *files* (the `iconPath` overload): the
 * composable-icon overloads render through a skiko `Image.encodeToData`
 * signature that no longer exists in the skiko shipped with Compose 1.12, and
 * crash with `NoSuchMethodError` until ComposeNativeTray is rebuilt against it.
 */
fun main(args: Array<String>) =
    nucleusApplication(args) {
        val trayIconPng = remember { extractToTempFile("/tray-icon.png", ".png") }
        val trayIconIco = remember { extractToTempFile("/tray-icon.ico", ".ico") }
        val state =
            rememberWindowState(
                position = WindowPosition.Aligned(Alignment.BottomEnd),
                size = DpSize(360.dp, 160.dp),
            )
        var corner by remember { mutableStateOf<Alignment>(Alignment.BottomEnd) }
        var watermarkVisible by remember { mutableStateOf(true) }

        fun moveTo(alignment: Alignment) {
            corner = alignment
            state.position = WindowPosition.Aligned(alignment)
        }

        Tray(
            iconPath = trayIconPng,
            windowsIconPath = trayIconIco,
            tooltip = "Nucleus watermark",
        ) {
            CheckableItem(
                label = "Show watermark",
                checked = watermarkVisible,
                onCheckedChange = { watermarkVisible = it },
            )
            Divider()
            CheckableItem(
                label = "Top left",
                checked = corner == Alignment.TopStart,
                onCheckedChange = { moveTo(Alignment.TopStart) },
            )
            CheckableItem(
                label = "Top right",
                checked = corner == Alignment.TopEnd,
                onCheckedChange = { moveTo(Alignment.TopEnd) },
            )
            CheckableItem(
                label = "Bottom left",
                checked = corner == Alignment.BottomStart,
                onCheckedChange = { moveTo(Alignment.BottomStart) },
            )
            CheckableItem(
                label = "Bottom right",
                checked = corner == Alignment.BottomEnd,
                onCheckedChange = { moveTo(Alignment.BottomEnd) },
            )
            Divider()
            Item(label = "Quit") { exitApplication() }
        }

        var overlaySurface by remember { mutableStateOf<String?>(null) }

        MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
            MaterialDecoratedWindow(
                onCloseRequest = ::exitApplication,
                state = rememberWindowState(size = DpSize(440.dp, 470.dp)),
                title = "Watermark control",
            ) {
                TitleBar {
                    Text("Watermark control", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                }
                ControlPanel(
                    watermarkVisible = watermarkVisible,
                    onWatermarkVisibleChange = { watermarkVisible = it },
                    corner = corner,
                    onCornerChange = ::moveTo,
                    controlSurface = surfaceLabel(nucleusWindow.unsafe.taoWindow),
                    overlaySurface = overlaySurface,
                )
            }
        }

        if (watermarkVisible) {
            DecoratedWindow(
                onCloseRequest = { watermarkVisible = false },
                state = state,
                title = "Watermark",
                resizable = false,
                alwaysOnTop = true,
                undecorated = true,
                transparent = true,
                hiddenFromDock = true,
                focusable = false,
                // A watermark must never get in the way: clicks fall through to
                // whatever is underneath and the window never takes focus.
                clickThrough = true,
                // …and it follows the user across desktops. macOS/Linux need this
                // explicitly; on Windows a taskbar-excluded window already shows on
                // every virtual desktop.
                visibleOnAllWorkspaces = true,
                // Linux: everything above needs an X11 surface. Only this window
                // gets one — the control window stays on the session's compositor.
                forceX11 = true,
            ) {
                LaunchedEffect(nucleusWindow) { overlaySurface = surfaceLabel(nucleusWindow.unsafe.taoWindow) }
                AnimatedWatermark()
            }
        }
    }

/**
 * Copies a classpath resource to a temp file and returns it as a `file:` URI
 * string — ComposeNativeTray parses `iconPath` through `java.net.URI`, which
 * rejects raw Windows paths (`C:\…` reads as an opaque `C:` scheme).
 */
private fun extractToTempFile(
    resource: String,
    suffix: String,
): String {
    val bytes =
        checkNotNull(object {}.javaClass.getResourceAsStream(resource)) {
            "Missing resource $resource"
        }.use { it.readBytes() }
    val file = File.createTempFile("watermark-tray", suffix)
    file.writeBytes(bytes)
    file.deleteOnExit()
    return file.toURI().toString()
}

/**
 * The watermark pill: a translucent rounded capsule whose "NUCLEUS" label
 * breathes (alpha pulse) while a light shimmer sweeps across the glyphs.
 * Everything outside the pill stays at alpha 0 — over a transparent window
 * that means the desktop.
 */
@Composable
private fun AnimatedWatermark() {
    val transition = rememberInfiniteTransition(label = "watermark")
    val pulse by transition.animateFloat(
        initialValue = 0.45f,
        targetValue = 1f,
        animationSpec =
            infiniteRepeatable(
                animation = tween(durationMillis = 1600, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse,
            ),
        label = "pulse",
    )
    val sweep by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec =
            infiniteRepeatable(
                animation = tween(durationMillis = 2600, easing = LinearEasing),
            ),
        label = "sweep",
    )
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        // Sweep a bright band across the text; the band travels a bit past
        // both ends so the shimmer fully leaves the glyphs between passes.
        val sweepX = sweep * 700f - 200f
        val shimmer =
            Brush.linearGradient(
                colors = listOf(Color(0x66FFFFFF), Color.White, Color(0x66FFFFFF)),
                start = Offset(sweepX - 150f, 0f),
                end = Offset(sweepX + 150f, 140f),
            )
        BasicText(
            text = "NUCLEUS",
            modifier =
                Modifier
                    .graphicsLayer { alpha = pulse }
                    .clip(RoundedCornerShape(28.dp))
                    .background(Color.Black.copy(alpha = 0.35f))
                    .padding(horizontal = 28.dp, vertical = 14.dp),
            style =
                TextStyle(
                    brush = shimmer,
                    fontSize = 34.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 8.sp,
                ),
        )
    }
}

/**
 * Which windowing system actually backs a window, read from the live surface
 * rather than from the environment — the whole point of `forceX11` is that the
 * answer differs between two windows of the same process. `null` on
 * macOS/Windows, where the question does not arise.
 */
private fun surfaceLabel(window: TaoWindow?): String? {
    if (window == null || Platform.Current != Platform.Linux) return null
    return if (window.isNativeWaylandSurface) "Wayland" else "X11"
}

/**
 * The control window's content: toggle the overlay, pick the corner it is
 * pinned to, and see which surface each of the two windows ended up on.
 */
@Composable
private fun ControlPanel(
    watermarkVisible: Boolean,
    onWatermarkVisibleChange: (Boolean) -> Unit,
    corner: Alignment,
    onCornerChange: (Alignment) -> Unit,
    controlSurface: String?,
    overlaySurface: String?,
) {
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surface)
                .padding(horizontal = 24.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text("Watermark overlay", style = MaterialTheme.typography.titleMedium)
                Text(
                    text = "Transparent, click-through, always on top",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = watermarkVisible, onCheckedChange = onWatermarkVisibleChange)
        }

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                text = "Pinned corner",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            CornerPicker(corner = corner, enabled = watermarkVisible, onCornerChange = onCornerChange)
        }

        if (controlSurface != null) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Surfaces",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SurfaceRow("This window", controlSurface)
                SurfaceRow("Overlay", overlaySurface ?: "—")
            }
        }
    }
}

/** A miniature screen: click a corner to pin the overlay there. */
@Composable
private fun CornerPicker(
    corner: Alignment,
    enabled: Boolean,
    onCornerChange: (Alignment) -> Unit,
) {
    val corners =
        listOf(
            Alignment.TopStart to Alignment.TopStart,
            Alignment.TopEnd to Alignment.TopEnd,
            Alignment.BottomStart to Alignment.BottomStart,
            Alignment.BottomEnd to Alignment.BottomEnd,
        )
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(150.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                .border(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.outlineVariant,
                    shape = RoundedCornerShape(14.dp),
                ).padding(10.dp),
    ) {
        corners.forEach { (alignment, value) ->
            val selected = corner == value
            Box(
                modifier =
                    Modifier
                        .align(alignment)
                        .size(width = 116.dp, height = 52.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(
                            if (selected) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.surface
                            },
                        ).clickable(enabled = enabled) { onCornerChange(value) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "NUCLEUS",
                    style = MaterialTheme.typography.labelSmall,
                    letterSpacing = 2.sp,
                    color =
                        if (selected) {
                            MaterialTheme.colorScheme.onPrimary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        },
                )
            }
        }
    }
}

/** `label — backend` line, with the backend rendered as a pill. */
@Composable
private fun SurfaceRow(
    label: String,
    backend: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Box(
            modifier =
                Modifier
                    .clip(RoundedCornerShape(50))
                    .background(MaterialTheme.colorScheme.secondaryContainer)
                    .padding(horizontal = 12.dp, vertical = 4.dp),
        ) {
            Text(
                text = backend,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }
}
