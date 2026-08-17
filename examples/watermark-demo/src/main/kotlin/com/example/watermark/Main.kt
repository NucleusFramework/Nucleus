package com.example.watermark

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
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

        DecoratedWindow(
            onCloseRequest = ::exitApplication,
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
        ) {
            AnimatedWatermark()
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
