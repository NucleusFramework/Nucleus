package com.example.widget

import androidx.compose.foundation.ContextMenuArea
import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState
import dev.nucleusframework.application.DecoratedWindow
import dev.nucleusframework.application.contextmenu.ContextMenuIcon
import dev.nucleusframework.application.contextmenu.NucleusContextMenuDivider
import dev.nucleusframework.application.contextmenu.NucleusContextMenuItem
import dev.nucleusframework.application.nucleusApplication
import dev.nucleusframework.sfsymbols.SFSymbol
import dev.nucleusframework.sfsymbols.SFSymbolSecurity
import dev.nucleusframework.sfsymbols.SFSymbolStatus
import dev.nucleusframework.window.tao.TaoWindow
import kotlinx.coroutines.delay
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Desktop-widget demo (#564).
 *
 * Showcases `alwaysOnBottom` on the backend-agnostic `DecoratedWindow`: a single
 * borderless, transparent, taskbar-hidden window holding the animated Nucleus
 * atom and a clock, stacked *below* every other window — the wallpaper-level
 * counterpart of `watermark-demo`, which pins the same kind of overlay above
 * everything.
 *
 * The widget is its own UI: drag it anywhere by its card, right-click it for the
 * native context menu (`nativeContextMenu`) to lock the position or close it.
 * No control window, no tray icon.
 *
 * **Linux**: below-stacking is X11-only. `gtk_window_set_keep_below` maps to
 * `_NET_WM_STATE_BELOW`, which native Wayland has no equivalent for — xdg-shell
 * deliberately exposes no client-side stacking, and a surface genuinely glued to
 * the wallpaper would need `wlr-layer-shell` (`background` layer), which Tao
 * does not support. So the widget asks for an X11 surface of its own
 * (`forceX11`) instead of forcing the whole process onto XWayland with
 * `NUCLEUS_TAO_LINUX_RENDERER=x11`. On a Wayland surface the framework logs one
 * warning per feature and the widget just stacks normally.
 *
 * **Not a desktop widget in the shell sense**: below-stacking leaves the window
 * in Alt+Tab and in the pager. Real desktop integration would need
 * `_NET_WM_WINDOW_TYPE_DESKTOP`, which Tao does not expose; `hiddenFromDock`
 * covers the taskbar half of it.
 */
fun main(args: Array<String>) =
    nucleusApplication(args) {
        var locked by remember { mutableStateOf(false) }

        DecoratedWindow(
            onCloseRequest = ::exitApplication,
            state =
                rememberWindowState(
                    position = WindowPosition.Aligned(Alignment.BottomEnd),
                    size = DpSize(300.dp, 340.dp),
                ),
            title = "Nucleus widget",
            resizable = false,
            undecorated = true,
            transparent = true,
            hiddenFromDock = true,
            // Right-click menu drawn by the OS: NSMenu on macOS, an Adwaita /
            // Fluent flyout on Linux / Windows.
            nativeContextMenu = true,
            visibleOnAllWorkspaces = true,
            // Linux: keep_below is an X11 protocol — take an X11 surface for it.
            forceX11 = true,
            alwaysOnBottom = true,
        ) {
            AtomWidget(
                taoWindow = nucleusWindow.unsafe.taoWindow,
                locked = locked,
                onToggleLock = { locked = !locked },
                onClose = ::exitApplication,
            )
        }
    }

/**
 * The widget: the Nucleus atom over a translucent card, with a live clock.
 * Everything outside the card stays at alpha 0 — over a transparent window that
 * means the desktop shows through.
 *
 * The card is the drag handle. An undecorated window has no title bar to grab,
 * so a primary press hands the move over to the compositor
 * ([TaoWindow.dragWindow]): `gtk_window_begin_move_drag` on Linux,
 * `performWindowDragWithEvent` on macOS, `WM_NCLBUTTONDOWN` on Windows. It has
 * to fire on the *press* — a compositor move grab can only start inside one —
 * hence the raw pointer loop rather than a click handler, and the explicit
 * primary-button check so a right-click opens the menu instead of moving the
 * window.
 */
@Composable
private fun AtomWidget(
    taoWindow: TaoWindow?,
    locked: Boolean,
    onToggleLock: () -> Unit,
    onClose: () -> Unit,
) {
    var now by remember { mutableStateOf(LocalDateTime.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000)
            now = LocalDateTime.now()
        }
    }
    val time = remember { DateTimeFormatter.ofPattern("HH:mm:ss") }
    val date = remember { DateTimeFormatter.ofPattern("EEEE d MMMM") }

    ContextMenuArea(items = { widgetMenu(locked, onToggleLock, onClose) }) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(10.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(Color.Black.copy(alpha = 0.42f))
                    .pointerHoverIcon(if (locked) PointerIcon.Default else PointerIcon.Hand)
                    .pointerInput(taoWindow, locked) {
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Main)
                                if (locked || event.type != PointerEventType.Press) continue
                                if (event.buttons.isPrimaryPressed) taoWindow?.dragWindow()
                            }
                        }
                    }.padding(vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            NucleusAtom(atomSize = 210.dp)
            BasicText(
                text = now.format(time),
                style =
                    TextStyle(
                        color = Color.White,
                        fontSize = 30.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 2.sp,
                        textAlign = TextAlign.Center,
                    ),
            )
            BasicText(
                text = if (locked) "${now.format(date)} · locked" else now.format(date),
                style =
                    TextStyle(
                        color = Color.White.copy(alpha = 0.72f),
                        fontSize = 13.sp,
                        textAlign = TextAlign.Center,
                    ),
            )
        }
    }
}

/**
 * Right-click menu of the widget. [NucleusContextMenuItem] carries the icons the
 * native renderer draws (SF Symbols on macOS; ignored elsewhere) — a plain
 * `ContextMenuItem` would work just as well without them.
 *
 * The symbol names come from the `sf-symbols` catalog rather than string
 * literals: `ContextMenuIcon.SfSymbol` takes a raw name, so [SFSymbol.symbolName]
 * is what keeps the call honest about the symbol actually existing.
 */
private fun widgetMenu(
    locked: Boolean,
    onToggleLock: () -> Unit,
    onClose: () -> Unit,
): List<ContextMenuItem> =
    listOf(
        NucleusContextMenuItem(
            label = if (locked) "Unlock position" else "Lock position",
            icon = sfSymbol(if (locked) SFSymbolSecurity.LOCK_OPEN else SFSymbolSecurity.LOCK),
            onClick = onToggleLock,
        ),
        NucleusContextMenuDivider,
        NucleusContextMenuItem(
            label = "Close widget",
            icon = sfSymbol(SFSymbolStatus.XMARK),
            onClick = onClose,
        ),
    )

private fun sfSymbol(symbol: SFSymbol): ContextMenuIcon = ContextMenuIcon.SfSymbol(symbol.symbolName)
