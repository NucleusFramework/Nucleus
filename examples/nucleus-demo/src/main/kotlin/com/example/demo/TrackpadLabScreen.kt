package com.example.demo

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState
import dev.nucleusframework.application.LocalNucleusApplicationScope
import dev.nucleusframework.core.runtime.Platform
import dev.nucleusframework.webview.web.WebView
import dev.nucleusframework.webview.web.rememberWebViewNavigator
import dev.nucleusframework.webview.web.rememberWebViewStateWithHTMLData
import dev.nucleusframework.window.macOSLargeCornerRadius
import dev.nucleusframework.window.material.MaterialDecoratedWindow
import dev.nucleusframework.window.material.MaterialTitleBar
import dev.nucleusframework.window.newFullscreenControls
import kotlin.math.max

/**
 * Manual test rig for scroll input on the Tao backend — the three macOS
 * trackpad issues (#652 sign, #653 magnitude, #654 Pan vs Scroll) side by
 * side, each with the expected behaviour written next to it:
 *
 *  - **Inspector**: every `Scroll` / `PanStart` / `PanMove` / `PanEnd`
 *    reaching Compose at the root, with the gap since the previous event,
 *    counters, and one summary per gesture (steps, distance in wheel units,
 *    how long after the last move the `PanEnd` arrived — ~150 ms means the
 *    grace timer closed it, ~0 ms means AppKit's momentum tail did).
 *  - **Sign & magnitude**: a vertical column and a horizontal row; fingers
 *    up / left must make the offsets grow, one wheel notch must move exactly
 *    `10 dp`.
 *  - **Map canvas**: pans on Pan events, zooms on Scroll — the MapLibre use
 *    case. A trackpad swipe that zooms means #654 is back.
 *  - **Popup**: a scrollable `DropdownMenu`; inline in the main window, an
 *    NSPanel in the window opened with native popup layers.
 *  - **NativeView**: a WKWebView with a long page and its own HUD (scrollY,
 *    wheel events, last deltaY) — the native child must follow a two-finger
 *    swipe, keep its momentum and rubber-band at the ends.
 *
 * `-Dnucleus.tao.trackpadPanEvents=false` (shown in the header) turns every
 * gesture step back into `Scroll`, AWT style.
 */
@Composable
fun TrackpadLabScreen(onOpenNativePopupWindow: () -> Unit) {
    TrackpadLab(nativePopups = false, onOpenNativePopupWindow = onOpenNativePopupWindow)
}

/** The same lab in a window created with `nativePopupLayers = true` (popups become NSPanels on macOS). */
@Composable
fun TrackpadLabWindow(
    visible: Boolean,
    onCloseRequest: () -> Unit,
) {
    if (!visible) return
    val state =
        rememberWindowState(
            position = WindowPosition.Aligned(Alignment.Center),
            placement = WindowPlacement.Floating,
            size = DpSize(1400.dp, 920.dp),
        )
    val applicationScope = LocalNucleusApplicationScope.current
    applicationScope.MaterialDecoratedWindow(
        state = state,
        onCloseRequest = onCloseRequest,
        title = "Trackpad Lab — native popup layers",
        nativePopupLayers = true,
    ) {
        MaterialTitleBar(modifier = Modifier.newFullscreenControls().macOSLargeCornerRadius()) { _ ->
            Text("Trackpad Lab — popups are NSPanels here", style = MaterialTheme.typography.titleSmall)
        }
        TrackpadLab(nativePopups = true, onOpenNativePopupWindow = null)
    }
}

@Composable
private fun TrackpadLab(
    nativePopups: Boolean,
    onOpenNativePopupWindow: (() -> Unit)?,
) {
    val density = LocalDensity.current.density
    val log = remember { PointerLog() }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    // Initial pass, never consuming: sees what every child will
                    // get, scrolling below still happens normally.
                    .pointerInput(log) {
                        awaitPointerEventScope {
                            while (true) {
                                log.record(awaitPointerEvent(PointerEventPass.Initial), density)
                            }
                        }
                    }.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            LabHeader(density, nativePopups, onOpenNativePopupWindow, onReset = log::reset)
            Row(
                modifier = Modifier.fillMaxWidth().weight(1f),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                InspectorPanel(log, modifier = Modifier.weight(1.15f).fillMaxHeight())
                SignAndMagnitudePanel(density, modifier = Modifier.weight(1f).fillMaxHeight())
                Column(
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    MapCanvasPanel(modifier = Modifier.weight(1f).fillMaxWidth().heightIn(min = MAP_MIN_HEIGHT_DP.dp))
                    PopupPanel(nativePopups)
                }
            }
            NativeViewPanel(modifier = Modifier.fillMaxWidth().weight(WEBVIEW_WEIGHT))
        }
    }
}

// ── Header ─────────────────────────────────────────────────────────────────

@Composable
private fun LabHeader(
    density: Float,
    nativePopups: Boolean,
    onOpenNativePopupWindow: (() -> Unit)?,
    onReset: () -> Unit,
) {
    val panEvents = System.getProperty("nucleus.tao.trackpadPanEvents", "true").toBoolean()
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Trackpad Lab", style = MaterialTheme.typography.titleMedium)
        Mono(
            "os=${Platform.Current}  density=${"%.2f".format(density)}  " +
                "px/wheel-unit=${"%.0f".format(PAN_DP_PER_WHEEL_UNIT_LAB * density)}  " +
                "trackpadPanEvents=$panEvents  popups=${if (nativePopups) "NSPanel" else "inline"}",
        )
        Spacer(Modifier.weight(1f))
        OutlinedButton(onClick = onReset) { Text("Reset") }
        if (onOpenNativePopupWindow != null) {
            Button(onClick = onOpenNativePopupWindow) { Text("Open with native popup layers") }
        }
    }
    if (!panEvents) {
        Text(
            "Pan events are OFF (-Dnucleus.tao.trackpadPanEvents=false): every gesture step arrives as Scroll, AWT style.",
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

// ── Inspector ──────────────────────────────────────────────────────────────

private class GestureSummary(
    val index: Int,
    val steps: Int,
    val wheelUnits: Offset,
    val durationMs: Long,
    val longestGapMs: Long,
    val endAfterLastMoveMs: Long,
)

/** Root-level observation of what Compose receives; UI-thread only. */
private class PointerLog {
    val lines = mutableStateListOf<String>()
    val gestures = mutableStateListOf<GestureSummary>()
    var panStarts by mutableIntStateOf(0)
    var panMoves by mutableIntStateOf(0)
    var panEnds by mutableIntStateOf(0)
    var scrolls by mutableIntStateOf(0)

    private var gestureIndex = 0
    private var lastEventMs = 0L
    private var gestureStartMs = 0L
    private var lastMoveMs = 0L
    private var steps = 0
    private var sumPx = Offset.Zero
    private var longestGapMs = 0L

    fun record(
        event: PointerEvent,
        density: Float,
    ) {
        val change = event.changes.firstOrNull() ?: return
        val now = System.nanoTime() / NANOS_PER_MILLI
        val gap = if (lastEventMs == 0L) 0L else now - lastEventMs
        val unitPx = PAN_DP_PER_WHEEL_UNIT_LAB * density
        when (event.type) {
            PointerEventType.PanStart -> {
                panStarts++
                gestureStartMs = now
                lastMoveMs = now
                steps = 0
                sumPx = Offset.Zero
                longestGapMs = 0L
                add(gap, "PanStart")
            }
            PointerEventType.PanMove -> {
                panMoves++
                steps++
                sumPx += change.panOffset
                longestGapMs = max(longestGapMs, now - lastMoveMs)
                lastMoveMs = now
                add(gap, "PanMove   Δpx=${change.panOffset.fmt()}   =${(change.panOffset / unitPx).fmt()} wheel units")
            }
            PointerEventType.PanEnd -> {
                panEnds++
                val endAfter = now - lastMoveMs
                gestures.add(
                    0,
                    GestureSummary(
                        index = ++gestureIndex,
                        steps = steps,
                        wheelUnits = sumPx / unitPx,
                        durationMs = now - gestureStartMs,
                        longestGapMs = longestGapMs,
                        endAfterLastMoveMs = endAfter,
                    ),
                )
                if (gestures.size > MAX_GESTURES) gestures.removeAt(gestures.lastIndex)
                add(gap, "PanEnd    (+$endAfter ms after the last move)")
            }
            PointerEventType.Scroll -> {
                scrolls++
                add(
                    gap,
                    "Scroll    Δ=${change.scrollDelta.fmt()} wheel units   =${(change.scrollDelta * unitPx).fmt()} px",
                )
            }
            else -> return
        }
        lastEventMs = now
    }

    fun reset() {
        lines.clear()
        gestures.clear()
        panStarts = 0
        panMoves = 0
        panEnds = 0
        scrolls = 0
        lastEventMs = 0L
    }

    private fun add(
        gapMs: Long,
        text: String,
    ) {
        lines.add(0, "+%4d ms  %s".format(gapMs, text))
        if (lines.size > MAX_LINES) lines.removeAt(lines.lastIndex)
    }
}

@Composable
private fun InspectorPanel(
    log: PointerLog,
    modifier: Modifier = Modifier,
) {
    Panel("Inspector — what Compose receives at the root", modifier) {
        Mono(
            "PanStart ${log.panStarts}   PanMove ${log.panMoves}   PanEnd ${log.panEnds}   Scroll ${log.scrolls}",
            bold = true,
        )
        Text(
            "Trackpad ⇒ PanStart, PanMove…, ONE PanEnd (end ≈0 ms: momentum closed it, ≈150 ms: grace timer). " +
                "Wheel ⇒ Scroll only.",
            style = MaterialTheme.typography.bodySmall,
        )
        Mono("#   steps  Σ units (x, y)     dur     gap     end", bold = true)
        log.gestures.forEach { g ->
            Mono(
                "%-3d %-6d %-18s %4dms  %4dms  %4dms".format(
                    g.index,
                    g.steps,
                    g.wheelUnits.fmt(),
                    g.durationMs,
                    g.longestGapMs,
                    g.endAfterLastMoveMs,
                ),
            )
        }
        Mono("event log (newest first)", bold = true)
        log.lines.forEach { Mono(it) }
    }
}

// ── Sign & magnitude ───────────────────────────────────────────────────────

@Composable
private fun SignAndMagnitudePanel(
    density: Float,
    modifier: Modifier = Modifier,
) {
    Panel("Sign & magnitude — #652 / #653", modifier) {
        val vertical = rememberScrollState()
        val horizontal = rememberScrollState()
        Mono("vertical ${vertical.value} px — fingers UP ⇒ grows", bold = true)
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .heightIn(min = STRIP_MIN_HEIGHT_DP.dp)
                    .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(6.dp))
                    .verticalScroll(vertical),
        ) {
            repeat(STRIP_CELLS) { i ->
                Text(
                    "Row %03d".format(i),
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .background(if (i % 2 == 0) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent)
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        Mono("horizontal ${horizontal.value} px — fingers LEFT ⇒ grows (#652)", bold = true)
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(STRIP_HEIGHT_DP.dp)
                    .padding(top = 2.dp)
                    .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(6.dp))
                    .horizontalScroll(horizontal),
        ) {
            repeat(STRIP_CELLS) { i ->
                Box(
                    Modifier
                        .width(STRIP_CELL_DP.dp)
                        .fillMaxHeight()
                        .background(if (i % 2 == 0) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent),
                    contentAlignment = Alignment.Center,
                ) { Text("%02d".format(i), style = MaterialTheme.typography.bodySmall) }
            }
        }
        Text(
            "1 wheel notch = 10 dp = ${"%.0f".format(
                PAN_DP_PER_WHEEL_UNIT_LAB * density,
            )} px = a 10-point trackpad step, " +
                "on any display scale (#653).",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

// ── Map canvas ─────────────────────────────────────────────────────────────

@Composable
private fun MapCanvasPanel(modifier: Modifier = Modifier) {
    var offset by remember { mutableStateOf(Offset.Zero) }
    var zoom by remember { mutableFloatStateOf(1f) }
    Panel("Map canvas — #654: trackpad pans, wheel zooms", modifier) {
        Mono("offset=${offset.fmt()} px   zoom=${"%.2f".format(zoom)}", bold = true)
        Text("Two fingers move the grid (never zoom); a wheel notch zooms.", style = MaterialTheme.typography.bodySmall)
        Canvas(
            modifier =
                Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(0xFF10131A))
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull() ?: continue
                                when (event.type) {
                                    // Content follows the fingers: positive panOffset
                                    // means "scroll down / right", so the grid moves
                                    // up / left.
                                    PointerEventType.PanMove -> {
                                        offset -= change.panOffset
                                        change.consume()
                                    }
                                    PointerEventType.PanStart, PointerEventType.PanEnd -> change.consume()
                                    PointerEventType.Scroll -> {
                                        zoom =
                                            (zoom * (1f - change.scrollDelta.y * ZOOM_PER_NOTCH)).coerceIn(
                                                MIN_ZOOM,
                                                MAX_ZOOM,
                                            )
                                        change.consume()
                                    }
                                    else -> Unit
                                }
                            }
                        }
                    },
        ) {
            val spacing = GRID_SPACING_DP.dp.toPx() * zoom
            val origin = Offset(size.width / 2f, size.height / 2f) + offset
            val startX = ((origin.x % spacing) + spacing) % spacing
            val startY = ((origin.y % spacing) + spacing) % spacing
            var x = startX
            while (x < size.width) {
                drawLine(Color(0xFF2A3142), Offset(x, 0f), Offset(x, size.height))
                x += spacing
            }
            var y = startY
            while (y < size.height) {
                drawLine(Color(0xFF2A3142), Offset(0f, y), Offset(size.width, y))
                y += spacing
            }
            // The world origin: a landmark that must stay under the fingers.
            drawCircle(Color(0xFFFF5252), radius = 6.dp.toPx() * zoom, center = origin)
            drawLine(Color(0xFF80D8FF), origin - Offset(spacing, 0f), origin + Offset(spacing, 0f), strokeWidth = 2f)
            drawLine(Color(0xFF80D8FF), origin - Offset(0f, spacing), origin + Offset(0f, spacing), strokeWidth = 2f)
        }
    }
}

// ── Popup ──────────────────────────────────────────────────────────────────

@Composable
private fun PopupPanel(nativePopups: Boolean) {
    var expanded by remember { mutableStateOf(false) }
    Panel("Popup — ${if (nativePopups) "NSPanel (native popup layer)" else "inline layer"}", Modifier.fillMaxWidth()) {
        Box {
            OutlinedButton(onClick = { expanded = true }) { Text("Open a 40-item list and two-finger scroll it") }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                repeat(POPUP_ITEMS) { i ->
                    DropdownMenuItem(text = { Text("Item %02d".format(i)) }, onClick = { expanded = false })
                }
            }
        }
    }
}

// ── NativeView ─────────────────────────────────────────────────────────────

@Composable
private fun NativeViewPanel(modifier: Modifier = Modifier) {
    Panel("NativeView — embedded WKWebView, HUD drawn by the page itself", modifier) {
        Text(
            "The page must follow two fingers, keep its momentum after they lift and rubber-band at the ends; " +
                "the HUD counts the wheel events the native view gets.",
            style = MaterialTheme.typography.bodySmall,
        )
        Box(Modifier.fillMaxSize().clip(RoundedCornerShape(6.dp))) {
            WebView(
                state = rememberWebViewStateWithHTMLData(LAB_HTML),
                navigator = rememberWebViewNavigator(),
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

// ── Helpers ────────────────────────────────────────────────────────────────

@Composable
private fun Panel(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    Column(
        modifier =
            modifier
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        content()
    }
}

@Composable
private fun Mono(
    text: String,
    bold: Boolean = false,
) {
    Text(
        text,
        fontFamily = FontFamily.Monospace,
        fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
        style = MaterialTheme.typography.bodySmall,
        maxLines = 1,
    )
}

// `+ 0f` folds IEEE -0.0 (a negated zero wire delta) into 0.0 for display.
private fun Offset.fmt(): String = "(%.1f, %.1f)".format(x + 0f, y + 0f)

private const val NANOS_PER_MILLI = 1_000_000L

/** Compose Desktop's `MacOSCocoaConfig` factor; Nucleus sizes trackpad pans the same way. */
private const val PAN_DP_PER_WHEEL_UNIT_LAB = 10f
private const val MAX_LINES = 26
private const val MAX_GESTURES = 6
private const val STRIP_CELLS = 120
private const val STRIP_CELL_DP = 48
private const val STRIP_HEIGHT_DP = 40
private const val STRIP_MIN_HEIGHT_DP = 72
private const val WEBVIEW_WEIGHT = 0.8f
private const val MAP_MIN_HEIGHT_DP = 110
private const val POPUP_ITEMS = 40
private const val GRID_SPACING_DP = 48
private const val ZOOM_PER_NOTCH = 0.1f
private const val MIN_ZOOM = 0.25f
private const val MAX_ZOOM = 8f

// No template literals in the JS below: `${` would be a Kotlin template.
private val LAB_HTML =
    """
    <!doctype html><html><head><meta charset="utf-8"><style>
    body{margin:0;font:14px -apple-system,Helvetica,sans-serif;background:#111;color:#ddd}
    .row{padding:10px 16px;border-bottom:1px solid #222}.row:nth-child(even){background:#181818}
    #hud{position:fixed;top:8px;right:8px;background:rgba(0,0,0,.78);color:#9f9;padding:8px 10px;
         border-radius:8px;font:12px ui-monospace,Menlo,monospace;white-space:pre}
    </style></head><body><div id="hud"></div>
    <script>
    var wheel=0,lastDy=0,lastMode=0,lastT=0,maxGap=0,bursts=0;
    var hud=document.getElementById('hud');
    function paint(){
      hud.textContent='scrollY   '+Math.round(window.scrollY)+'\n'+
                      'wheel ev  '+wheel+'   bursts '+bursts+'\n'+
                      'last dY   '+lastDy.toFixed(2)+'  (deltaMode '+lastMode+')\n'+
                      'max gap   '+maxGap+' ms (within a burst)';
    }
    window.addEventListener('wheel',function(e){
      var t=performance.now();
      if(!lastT||t-lastT>400){bursts++;}else{maxGap=Math.max(maxGap,Math.round(t-lastT));}
      lastT=t;wheel++;lastDy=e.deltaY;lastMode=e.deltaMode;paint();
    },{passive:true});
    window.addEventListener('scroll',paint,{passive:true});
    for(var i=0;i<300;i++){
      var d=document.createElement('div');d.className='row';
      d.textContent='Native WKWebView row '+('00'+i).slice(-3)+' - two-finger scroll here: the page must follow, keep its momentum, rubber-band at the ends';
      document.body.appendChild(d);
    }
    paint();
    </script></body></html>
    """.trimIndent()
