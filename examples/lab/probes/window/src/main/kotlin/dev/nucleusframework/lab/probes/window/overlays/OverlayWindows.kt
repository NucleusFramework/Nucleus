package dev.nucleusframework.lab.probes.window.overlays

import androidx.compose.foundation.ContextMenuArea
import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.nucleusframework.application.DecoratedWindow
import dev.nucleusframework.application.NucleusApplicationScope
import dev.nucleusframework.application.contextmenu.ContextMenuIcon
import dev.nucleusframework.application.contextmenu.NucleusContextMenuDivider
import dev.nucleusframework.application.contextmenu.NucleusContextMenuItem
import dev.nucleusframework.lab.designsystem.LabTheme
import dev.nucleusframework.lab.designsystem.Text
import dev.nucleusframework.sfsymbols.SFSymbol
import dev.nucleusframework.sfsymbols.SFSymbolSecurity
import dev.nucleusframework.sfsymbols.SFSymbolStatus
import kotlinx.coroutines.delay
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * A passive overlay above everything: transparent, undecorated, never focused, and —
 * while click-through — never hit. Any press that reaches it is counted, so a
 * click-through that leaks shows up as a number instead of a feeling.
 */
@Composable
internal fun NucleusApplicationScope.WatermarkWindow(
    vm: OverlaysViewModel,
    close: () -> Unit,
) {
    val state by vm.state.collectAsState()
    val config = state.watermark
    key(config.forceX11) {
        DecoratedWindow(
            onCloseRequest = close,
            state = vm.watermarkState,
            title = "Lab watermark",
            resizable = false,
            alwaysOnTop = config.alwaysOnTop,
            undecorated = true,
            transparent = true,
            hiddenFromDock = true,
            focusable = false,
            clickThrough = config.clickThrough,
            visibleOnAllWorkspaces = config.allWorkspaces,
            forceX11 = config.forceX11,
        ) {
            val tao = nucleusWindow.unsafe.taoWindow
            LaunchedEffect(tao) { tao?.let { vm.onIntent(OverlaysIntent.Attached(Overlay.Watermark, it)) } }
            Box(
                Modifier.fillMaxSize().pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            if (awaitPointerEvent(PointerEventPass.Initial).type == PointerEventType.Press) {
                                vm.onIntent(OverlaysIntent.WatermarkPressed)
                            }
                        }
                    }
                },
                contentAlignment = Alignment.Center,
            ) {
                // Everything outside the pill stays at alpha 0: the desktop shows through. The pill
                // is the specimen, so its ink is fixed whatever the Lab theme.
                Text(
                    if (config.clickThrough) "NUCLEUS LAB" else "NUCLEUS LAB · clickable",
                    Modifier
                        .clip(RoundedCornerShape(50))
                        .background(Color(0x99202020))
                        .padding(horizontal = 24.dp, vertical = 12.dp),
                    style = LabTheme.typography.title.copy(fontSize = 22.sp, letterSpacing = 4.sp),
                    color = Color.White,
                )
            }
        }
    }
}

/**
 * A desktop widget below everything: dragged by its card through the compositor
 * (`TaoWindow.dragWindow`, which must start on the press), right-click for the native
 * context menu with SF Symbols.
 */
@Composable
internal fun NucleusApplicationScope.WidgetWindow(
    vm: OverlaysViewModel,
    close: () -> Unit,
) {
    val state by vm.state.collectAsState()
    val config = state.widget
    key(config.forceX11) {
        DecoratedWindow(
            onCloseRequest = close,
            state = vm.widgetState,
            title = "Lab widget",
            resizable = false,
            undecorated = true,
            transparent = true,
            hiddenFromDock = true,
            nativeContextMenu = true,
            visibleOnAllWorkspaces = config.allWorkspaces,
            forceX11 = config.forceX11,
            alwaysOnBottom = config.alwaysOnBottom,
        ) {
            val tao = nucleusWindow.unsafe.taoWindow
            LaunchedEffect(tao) { tao?.let { vm.onIntent(OverlaysIntent.Attached(Overlay.Widget, it)) } }
            var now by remember { mutableStateOf(LocalTime.now()) }
            LaunchedEffect(Unit) {
                while (true) {
                    delay(1_000)
                    now = LocalTime.now()
                }
            }
            ContextMenuArea(items = { widgetMenu(vm, config, close) }) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .padding(10.dp)
                        .clip(RoundedCornerShape(24.dp))
                        .background(Color.Black.copy(alpha = 0.45f))
                        .pointerInput(tao, config.locked) {
                            awaitPointerEventScope {
                                while (true) {
                                    val event = awaitPointerEvent(PointerEventPass.Main)
                                    if (config.locked ||
                                        event.type != PointerEventType.Press ||
                                        !event.buttons.isPrimaryPressed
                                    ) {
                                        continue
                                    }
                                    vm.onIntent(OverlaysIntent.WidgetDragStarted)
                                    tao?.dragWindow()
                                }
                            }
                        }.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
                ) {
                    Text(
                        now.format(TIME),
                        style = LabTheme.typography.title.copy(fontSize = 36.sp),
                        color = Color.White,
                    )
                    Text(
                        if (config.locked) "locked · right-click to unlock" else "drag me · right-click for the menu",
                        style = LabTheme.typography.small,
                        color = Color.White.copy(alpha = 0.75f),
                    )
                }
            }
        }
    }
}

private val TIME = DateTimeFormatter.ofPattern("HH:mm:ss")

private fun widgetMenu(
    vm: OverlaysViewModel,
    config: WidgetConfig,
    close: () -> Unit,
): List<ContextMenuItem> {
    fun pick(
        label: String,
        action: () -> Unit,
    ) = {
        vm.onIntent(OverlaysIntent.WidgetMenuPicked(label))
        action()
    }
    val lockLabel = if (config.locked) "Unlock position" else "Lock position"
    val stackLabel = if (config.alwaysOnBottom) "Stack normally" else "Pin below everything"
    return listOf(
        NucleusContextMenuItem(
            lockLabel,
            icon =
                sfSymbol(
                    if (config.locked) SFSymbolSecurity.LOCK_OPEN else SFSymbolSecurity.LOCK,
                ),
            onClick =
                pick(lockLabel) {
                    vm.onIntent(OverlaysIntent.SetWidget(config.copy(locked = !config.locked)))
                },
        ),
        NucleusContextMenuItem(
            stackLabel,
            onClick =
                pick(stackLabel) {
                    vm.onIntent(OverlaysIntent.SetWidget(config.copy(alwaysOnBottom = !config.alwaysOnBottom)))
                },
        ),
        NucleusContextMenuDivider,
        NucleusContextMenuItem(
            "Close widget",
            icon = sfSymbol(SFSymbolStatus.XMARK),
            onClick = pick("Close widget", close),
        ),
    )
}

private fun sfSymbol(symbol: SFSymbol): ContextMenuIcon = ContextMenuIcon.SfSymbol(symbol.symbolName)
