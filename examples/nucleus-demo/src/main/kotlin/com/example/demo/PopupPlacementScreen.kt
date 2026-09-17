package com.example.demo

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp
import dev.nucleusframework.window.tao.TaoMonitors
import dev.nucleusframework.window.tao.TaoWindow
import kotlinx.coroutines.delay

/**
 * Demo for issue #569 — popups positioned against the **screen**, not the
 * owner window.
 *
 * `nativePopupLayers = true` makes every Compose `Popup` / `DropdownMenu` a
 * real OS window, free to extend past the owner. The catch #569 fixed is that
 * Compose decided *where* to put it in window-rooted coordinates: it clipped
 * and flipped inside a work-area-sized box hanging off the window's content
 * origin, which is only the real screen when the window is maximized on the
 * primary display. Anywhere else, a menu anchored near the bottom of a window
 * sitting near the bottom of the display walked straight off it.
 *
 * The screen makes that visible with things a demo can actually show:
 *  - **park the window against a work-area edge** with one click,
 *  - **open a menu anchored at that same edge** and watch it stay on screen,
 *  - **open a dialog** and watch it stay centred on the *window* instead —
 *    a dialog belongs to its window, and only popups follow the display.
 *
 * Park the window bottom-right and open the bottom-right menu: the live
 * readout shows how little room is left below and to the right, and before the
 * fix the menu was drawn under the taskbar or off the right edge entirely.
 */
@Composable
fun PopupPlacementScreen(window: TaoWindow?) {
    var windowRect by remember { mutableStateOf<IntRect?>(null) }
    var workArea by remember { mutableStateOf<IntRect?>(null) }

    // Poll rather than listen: the point of the screen is to show the geometry
    // the popup layer re-reads on every frame push, including while the user
    // drags the window by its title bar.
    LaunchedEffect(window) {
        while (true) {
            windowRect = window?.outerRect()
            workArea = window?.let { TaoMonitors.forWindow(it).workAreaPx }
            delay(POLL_MILLIS)
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Screen-aware popup placement (#569)", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Every Popup below is a real OS window (nativePopupLayers = true). " +
                "Park this window against a work-area edge, then open the menu anchored at " +
                "that edge: it slides back inside the display instead of walking off it.",
            style = MaterialTheme.typography.bodyMedium,
        )

        GeometryCard(windowRect, workArea)

        Text("1 — park the window", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ParkButton("↖ top-left", window) { work, _ -> work.left to work.top }
            ParkButton("↗ top-right", window) { work, size -> (work.right - size.first) to work.top }
            ParkButton("center", window) { work, size ->
                (work.left + (work.width - size.first) / 2) to (work.top + (work.height - size.second) / 2)
            }
            ParkButton("↙ bottom-left", window) { work, size -> work.left to (work.bottom - size.second) }
            ParkButton("↘ bottom-right", window) { work, size ->
                (work.right - size.first) to (work.bottom - size.second)
            }
        }

        Text("2 — open a menu anchored at an edge", style = MaterialTheme.typography.titleMedium)
        // Anchors pinned to the corners of the *window content*: the geometry
        // that used to send a menu offscreen, because Compose measured the room
        // below/right of the anchor against a screen rooted at this window.
        Box(Modifier.fillMaxWidth().height(ANCHOR_BOX_DP.dp)) {
            EdgeMenu("top-left menu", Modifier.align(Alignment.TopStart))
            EdgeMenu("top-right menu", Modifier.align(Alignment.TopEnd))
            EdgeMenu("bottom-left menu", Modifier.align(Alignment.BottomStart))
            EdgeMenu("bottom-right menu", Modifier.align(Alignment.BottomEnd))
            EscapingPopupToggle(Modifier.align(Alignment.Center))
        }

        Text(
            "The oversized panel deliberately measures larger than this window — " +
                "a popup layer lays out against the work area, so it is not scrolled " +
                "down to the window's size, and the clamp keeps it on the display.",
            style = MaterialTheme.typography.bodySmall,
        )

        Text("3 — and a dialog is not a popup", style = MaterialTheme.typography.titleMedium)
        Text(
            "A Dialog goes through the very same native layer, but it belongs to its " +
                "window, not to the display: Compose centres it in the container size, so " +
                "the layer keeps reporting the window there. Park the window in a corner " +
                "and open it — it stays centred on the window, wherever that is.",
            style = MaterialTheme.typography.bodyMedium,
        )
        CenteredDialogToggle()
    }
}

/** A Material dialog, to show it stays centred on the window (see #569). */
@Composable
private fun CenteredDialogToggle() {
    var shown by remember { mutableStateOf(false) }
    Button(onClick = { shown = true }) { Text("open a centred dialog") }
    if (shown) {
        AlertDialog(
            onDismissRequest = { shown = false },
            confirmButton = { Button(onClick = { shown = false }) { Text("close") } },
            title = { Text("Centred on the window") },
            text = {
                Text(
                    "Not on the display — a window-owned dialog that drifted to the " +
                        "screen centre as you moved the window would be the bug, not the fix.",
                )
            },
        )
    }
}

@Composable
private fun GeometryCard(
    windowRect: IntRect?,
    workArea: IntRect?,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("live geometry (physical px)", style = MaterialTheme.typography.titleSmall)
            Text("window outer: ${windowRect?.describe() ?: "—"}")
            Text("display work area: ${workArea?.describe() ?: "—"}")
            val slack =
                if (windowRect != null && workArea != null) {
                    "${workArea.bottom - windowRect.bottom} px below, " +
                        "${workArea.right - windowRect.right} px to the right"
                } else {
                    "—"
                }
            Text("room left on the display: $slack")
            Text(
                "When that room is smaller than the menu, the clamp is what keeps it visible.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/**
 * Moves the window so [target] — computed from the work area and the window's
 * own outer size — becomes its top-left. The dp round-trip is deliberate:
 * `setOuterPosition` takes logical units, which is what an app would use.
 */
@Composable
private fun ParkButton(
    label: String,
    window: TaoWindow?,
    target: (work: IntRect, size: Pair<Int, Int>) -> Pair<Int, Int>,
) {
    OutlinedButton(
        enabled = window != null,
        onClick = {
            val w = window ?: return@OutlinedButton
            val rect = w.outerRect() ?: return@OutlinedButton
            val work = TaoMonitors.forWindow(w).workAreaPx
            val (x, y) = target(work, rect.width to rect.height)
            val scale = w.scaleFactor.takeIf { it > 0f } ?: 1f
            w.setOuterPosition(x / scale.toDouble(), y / scale.toDouble())
        },
    ) {
        Text(label)
    }
}

/** A `DropdownMenu` with enough items to be taller than the room at an edge. */
@Composable
private fun EdgeMenu(
    label: String,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        Button(onClick = { expanded = !expanded }) { Text(label) }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            repeat(MENU_ITEMS) { index ->
                DropdownMenuItem(
                    text = { Text("Menu entry ${index + 1}") },
                    onClick = { expanded = false },
                )
            }
        }
    }
}

/**
 * A popup deliberately larger than the owner window, anchored at its centre —
 * the "tray anchor" shape. Without native popup layers it would be clipped to
 * the window; with them it escapes, and with #569 it still stops at the
 * display's work area rather than at some window-rooted phantom edge.
 */
@Composable
private fun EscapingPopupToggle(modifier: Modifier = Modifier) {
    var shown by remember { mutableStateOf(false) }
    Box(modifier) {
        Button(onClick = { shown = !shown }) {
            Text(if (shown) "hide oversized panel" else "show oversized panel")
        }
        if (shown) {
            androidx.compose.ui.window.Popup(
                alignment = Alignment.TopStart,
                onDismissRequest = { shown = false },
            ) {
                Card {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Oversized popup", style = MaterialTheme.typography.titleMedium)
                        Text("Measured ${OVERSIZE_W_DP}×$OVERSIZE_H_DP dp — larger than this window.")
                        AssistChip(onClick = { shown = false }, label = { Text("dismiss") })
                        Spacer(Modifier.width(OVERSIZE_W_DP.dp).height(OVERSIZE_H_DP.dp))
                    }
                }
            }
        }
    }
}

private fun TaoWindow.outerRect(): IntRect? {
    val rect = outerBoundsPx() ?: return null
    if (rect.size < RECT_FIELDS) return null
    val left = rect[0].toInt()
    val top = rect[1].toInt()
    return IntRect(left, top, left + rect[2].toInt(), top + rect[3].toInt())
}

private fun IntRect.describe(): String = "$left, $top  $width×$height"

private const val POLL_MILLIS = 200L
private const val ANCHOR_BOX_DP = 320
private const val MENU_ITEMS = 14
private const val OVERSIZE_W_DP = 520
private const val OVERSIZE_H_DP = 420
private const val RECT_FIELDS = 4
