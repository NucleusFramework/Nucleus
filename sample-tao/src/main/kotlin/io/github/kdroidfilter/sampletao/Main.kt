package io.github.kdroidfilter.sampletao

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.draganddrop.dragAndDropSource
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.DragAndDropTransferAction
import androidx.compose.ui.draganddrop.DragAndDropTransferData
import androidx.compose.ui.draganddrop.DragAndDropTransferable
import androidx.compose.ui.draganddrop.awtTransferable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.rememberWindowState
import java.awt.datatransfer.StringSelection
import io.github.kdroidfilter.nucleus.application.DecoratedWindow
import io.github.kdroidfilter.nucleus.application.NucleusBackend
import io.github.kdroidfilter.nucleus.application.nucleusApplication
import io.github.kdroidfilter.nucleus.graalvm.GraalVmInitializer
import io.github.kdroidfilter.nucleus.window.NucleusDecoratedWindowTheme
import io.github.kdroidfilter.nucleus.window.TitleBar
import io.github.kdroidfilter.nucleus.window.macOSLargeCornerRadius
import io.github.kdroidfilter.nucleus.window.styling.TitleBarColors
import io.github.kdroidfilter.nucleus.window.styling.TitleBarMetrics
import io.github.kdroidfilter.nucleus.window.styling.TitleBarStyle
import io.github.kdroidfilter.sampleshared.*

fun main() {
    GraalVmInitializer.initialize()
    runApp()
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
@Composable
private fun DnDStage0Banner(onLog: (String) -> Unit) {
    val diag = io.github.kdroidfilter.nucleus.window.tao.TaoDnDDiagnostics
    var dropCount by remember { mutableStateOf(0) }
    var lastDrop by remember { mutableStateOf<String?>(null) }
    val dropTarget = remember {
        object : DragAndDropTarget {
            override fun onDrop(event: DragAndDropEvent): Boolean {
                dropCount++
                // Transparent AWT path — same code that works against
                // decorated-window-jni / standard Compose Desktop.
                lastDrop = runCatching {
                    @Suppress("UNCHECKED_CAST")
                    val files = event.awtTransferable
                        .getTransferData(java.awt.datatransfer.DataFlavor.javaFileListFlavor) as List<java.io.File>
                    "files=${files.size}: ${files.joinToString(limit = 2) { it.name }}"
                }.getOrElse { "error: ${it.message}" }
                onLog("[DnD] drop #$dropCount lastDrop=$lastDrop")
                return true
            }
            override fun onEntered(event: DragAndDropEvent) { onLog("[DnD] target onEntered") }
            override fun onExited(event: DragAndDropEvent)  { onLog("[DnD] target onExited") }
            override fun onStarted(event: DragAndDropEvent) { onLog("[DnD] target onStarted") }
            override fun onEnded(event: DragAndDropEvent)   { onLog("[DnD] target onEnded") }
        }
    }
    Row(
        modifier = Modifier.fillMaxWidth().height(56.dp).background(Color(0xFF1F2630))
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        BasicText(
            text = "DRAG ME",
            style = TextStyle(color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold),
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xFF3B82F6))
                .padding(horizontal = 14.dp, vertical = 8.dp)
                .dragAndDropSource(
                    transferData = { offset ->
                        onLog("[DnD] source transferData requested offset=$offset")
                        DragAndDropTransferData(
                            transferable = DragAndDropTransferable(StringSelection("hello-from-tao")),
                            supportedActions = listOf(DragAndDropTransferAction.Copy),
                            onTransferCompleted = { action ->
                                onLog("[DnD] source onTransferCompleted action=$action")
                            },
                        )
                    },
                ),
        )
        BasicText(
            text = "DROP HERE  (drops=$dropCount)",
            style = TextStyle(color = Color(0xFFE6E6E6), fontSize = 12.sp, fontWeight = FontWeight.Medium),
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xFF334155))
                .border(1.dp, Color(0xFF8AB4FF), RoundedCornerShape(8.dp))
                .padding(horizontal = 14.dp, vertical = 8.dp)
                .dragAndDropTarget(
                    shouldStartDragAndDrop = { true },
                    target = dropTarget,
                ),
        )
        BasicText(
            text = lastDrop ?: "(no drop yet)",
            style = TextStyle(color = Color(0xFFA0A4B0), fontSize = 11.sp),
        )
        Spacer(Modifier.weight(1f))
        BasicText(
            text = "mgr=${diag.constructed.intValue}  qry=${diag.isRequiredQueries.intValue}  " +
                "req=${diag.requests.intValue}  xfer=${diag.transfers.intValue}",
            style = TextStyle(color = Color(0xFF34D399), fontSize = 11.sp, fontWeight = FontWeight.Bold),
        )
    }
}

private fun runApp() = nucleusApplication(backend = NucleusBackend.Tao) {
    val previewEvents = remember { mutableStateListOf<String>() }
    var childRequest by remember { mutableStateOf<Pair<Boolean, Boolean>?>(null) }

    val titleBarStyle = TitleBarStyle(
        colors = TitleBarColors(
            background = Color(0xFF1A1D24),
            inactiveBackground = Color(0xFF15181D),
            content = Color(0xFFE6E6E6),
            border = Color.Transparent,
        ),
        metrics = TitleBarMetrics(height = 36.dp),
    )

    val mainState = rememberWindowState(size = DpSize(1024.dp, 720.dp))
    NucleusDecoratedWindowTheme(isDark = true, titleBarStyle = titleBarStyle) {
    DecoratedWindow(
        onCloseRequest = ::exitApplication,
        state = mainState,
        title = "Tao Backend Demo",
        minimumSize = DpSize(640.dp, 480.dp),
        onPreviewKeyEvent = { event ->
            // Demo: consume Cmd/Ctrl+K so it never reaches Compose. Other keys
            // are still logged but pass through.
            if (event.type == KeyEventType.KeyDown) {
                logEvent(previewEvents, "preview ${event.key}")
                if ((event.isMetaPressed || event.isCtrlPressed) && event.key == Key.K) {
                    return@DecoratedWindow true
                }
            }
            false
        },
    ) {
        val taoWindow = nucleusWindow.unsafe.taoWindow!!
        var clicks by remember { mutableStateOf(0) }
        val enabledBlobs = remember { mutableStateListOf(true, true, true, true) }
        var selectedTab by remember { mutableStateOf(Tab.Demo) }
        val events = previewEvents

        TitleBar(modifier = Modifier.macOSLargeCornerRadius()) { state ->
            Row(
                modifier = Modifier.align(Alignment.Start).padding(start = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Box(
                    modifier = Modifier.size(8.dp).clip(CircleShape)
                        .background(if (state.isActive) Color(0xFF34D399) else Color(0xFF6B7280)),
                )
                BasicText(
                    text = if (state.isActive) "Live" else "Inactive",
                    style = TextStyle(color = Color(0xFFA0A4B0), fontSize = 11.sp, fontWeight = FontWeight.Medium),
                )
            }

            BasicText(
                text = title,
                modifier = Modifier.align(Alignment.CenterHorizontally),
                style = TextStyle(
                    color = if (state.isActive) Color(0xFFE6E6E6) else Color(0xFFE6E6E6).copy(alpha = 0.5f),
                    fontSize = 12.sp, fontWeight = FontWeight.Medium,
                ),
            )

            Row(
                modifier = Modifier.align(Alignment.End).padding(end = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                PALETTE.forEachIndexed { idx, color ->
                    Box(
                        modifier = Modifier.size(14.dp).clip(CircleShape)
                            .background(if (enabledBlobs[idx]) color else color.copy(alpha = 0.18f))
                            .border(1.dp, if (enabledBlobs[idx]) color.copy(alpha = 0.4f) else Color.Transparent, CircleShape)
                            .clickable { enabledBlobs[idx] = !enabledBlobs[idx] },
                    )
                }
                Box(modifier = Modifier.size(width = 8.dp, height = 16.dp))
                BasicText(
                    text = "Clear",
                    style = TextStyle(color = Color(0xFF8AB4FF), fontSize = 11.sp, fontWeight = FontWeight.SemiBold),
                    modifier = Modifier.clip(RoundedCornerShape(6.dp))
                        .background(Color.White.copy(alpha = 0.06f))
                        .clickable { clicks = 0; events.clear() }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        }

        Column(modifier = Modifier.fillMaxSize().background(Color(0xFF0F1115))) {
            DnDStage0Banner(onLog = { logEvent(events, it) })
            TabBar(selectedTab, onSelect = { selectedTab = it })
            Box(modifier = Modifier.weight(1f).fillMaxSize()) {
                when (selectedTab) {
                    Tab.Demo -> FancyDemo(
                        modifier = Modifier.fillMaxSize(),
                        clicks = clicks,
                        onClick = { clicks++; logEvent(events, "click @ demo (#$clicks)") },
                        enabledBlobs = enabledBlobs,
                    )
                    Tab.Scroll -> ScrollTab(modifier = Modifier.fillMaxSize())
                    Tab.Actions -> ActionsTab(
                        modifier = Modifier.fillMaxSize(),
                        window = taoWindow,
                        placement = mainState.placement,
                        onPlacementChange = { mainState.placement = it },
                        onLog = { logEvent(events, it) },
                        onOpenChildWindow = { childEnabled, childFocusable ->
                            childRequest = childEnabled to childFocusable
                            logEvent(events, "openChildWindow(enabled=$childEnabled, focusable=$childFocusable)")
                        },
                    )
                    Tab.A11y -> A11yTab(modifier = Modifier.fillMaxSize())
                    Tab.Events -> EventsTab(modifier = Modifier.fillMaxSize(), events = events)
                }
            }
        }
    }

    childRequest?.let { (childEnabled, childFocusable) ->
        DecoratedWindow(
            onCloseRequest = { childRequest = null },
            state = rememberWindowState(size = DpSize(480.dp, 240.dp)),
            title = "Child (enabled=$childEnabled, focusable=$childFocusable)",
            enabled = childEnabled,
            focusable = childFocusable,
        ) {
            Box(
                modifier = Modifier.fillMaxSize().background(Color(0xFF15171C)),
                contentAlignment = Alignment.Center,
            ) {
                BasicText(
                    text = buildString {
                        append("enabled=$childEnabled · focusable=$childFocusable\n")
                        if (!childEnabled) append("→ pointer + key events ignored\n")
                        if (!childFocusable) append("→ window can't become key")
                    },
                    style = TextStyle(color = Color(0xFFE6E6E6), fontSize = 13.sp),
                )
            }
        }
    }
    }
}

