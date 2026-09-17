package dev.nucleusframework.sampletao

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.draganddrop.dragAndDropSource
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.WindowPlacement
import dev.nucleusframework.application.DecoratedWindow
import dev.nucleusframework.application.nucleusApplication
import dev.nucleusframework.sampleshared.A11yTab
import dev.nucleusframework.sampleshared.ComplexTab
import dev.nucleusframework.sampleshared.EventsTab
import dev.nucleusframework.sampleshared.FancyDemo
import dev.nucleusframework.sampleshared.PALETTE
import dev.nucleusframework.sampleshared.ScrollTab
import dev.nucleusframework.sampleshared.Tab
import dev.nucleusframework.sampleshared.TabBar
import dev.nucleusframework.sampleshared.ZoomTab
import dev.nucleusframework.sampleshared.logEvent
import dev.nucleusframework.window.NucleusDecoratedWindowTheme
import dev.nucleusframework.window.TitleBar
import dev.nucleusframework.window.macOSLargeCornerRadius
import dev.nucleusframework.window.styling.TitleBarColors
import dev.nucleusframework.window.styling.TitleBarMetrics
import dev.nucleusframework.window.styling.TitleBarStyle
import dev.nucleusframework.window.tao.v2.WindowBoundsProvider
import dev.nucleusframework.window.tao.v2.WindowSizeProvider
import dev.nucleusframework.window.tao.v2.rememberWindowState
import java.awt.datatransfer.StringSelection

fun main() {
    // Issue #317 e2e: exercise an extract-and-load native lib (zstd-kmp) so the sandboxed packaging
    // pipeline's marker -> System.load rewrite -> NucleusSandboxLoader -> signed bundled copy path
    // is validated end-to-end. Result is printed to stdout (visible in the app-image console).
    // The probe is Java because Zstd.loadNativeLibrary() is Kotlin-internal (public at the JVM
    // level) and thus unreachable from Kotlin in another module.
    println("zstd-sandbox-roundtrip: ${ZstdSandboxProbe.roundtrip()}")
    runApp()
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
@Composable
private fun DnDStage0Banner(onLog: (String) -> Unit) {
    val diag = dev.nucleusframework.window.tao.TaoDnDDiagnostics
    var dropCount by remember { mutableStateOf(0) }
    var lastDrop by remember { mutableStateOf<String?>(null) }
    val dropTarget =
        remember {
            object : DragAndDropTarget {
                override fun onDrop(event: DragAndDropEvent): Boolean {
                    dropCount++
                    // Transparent AWT path — same code that works against
                    // the legacy AWT backend / standard Compose Desktop.
                    lastDrop =
                        runCatching {
                            @Suppress("UNCHECKED_CAST")
                            val files =
                                event.awtTransferable
                                    .getTransferData(
                                        java.awt.datatransfer.DataFlavor.javaFileListFlavor,
                                    ) as List<java.io.File>
                            "files=${files.size}: ${files.joinToString(limit = 2) { it.name }}"
                        }.getOrElse { "error: ${it.message}" }
                    onLog("[DnD] drop #$dropCount lastDrop=$lastDrop")
                    return true
                }

                override fun onEntered(event: DragAndDropEvent) {
                    onLog("[DnD] target onEntered")
                }

                override fun onExited(event: DragAndDropEvent) {
                    onLog("[DnD] target onExited")
                }

                override fun onStarted(event: DragAndDropEvent) {
                    onLog("[DnD] target onStarted")
                }

                override fun onEnded(event: DragAndDropEvent) {
                    onLog("[DnD] target onEnded")
                }
            }
        }
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(56.dp)
                .background(Color(0xFF1F2630))
                .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        BasicText(
            text = "DRAG TEXT",
            style = TextStyle(color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold),
            modifier =
                Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF3B82F6))
                    .padding(horizontal = 14.dp, vertical = 8.dp)
                    .dragAndDropSource(
                        transferData = { offset ->
                            onLog("[DnD] source transferData (text) offset=$offset")
                            DragAndDropTransferData(
                                transferable = DragAndDropTransferable(StringSelection("hello-from-tao")),
                                supportedActions = listOf(DragAndDropTransferAction.Copy),
                                onTransferCompleted = { onLog("[DnD] text export action=$it") },
                            )
                        },
                    ),
        )
        BasicText(
            text = "DRAG FILE",
            style = TextStyle(color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold),
            modifier =
                Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF8B5CF6))
                    .padding(horizontal = 14.dp, vertical = 8.dp)
                    .dragAndDropSource(
                        transferData = { offset ->
                            onLog("[DnD] source transferData (file) offset=$offset")
                            // Export a temp file we just write so the drop target
                            // gets something real to receive.
                            val tmp =
                                java.io.File.createTempFile("tao-export-", ".txt").apply {
                                    writeText("Exported from Tao backend at offset=$offset\n")
                                    deleteOnExit()
                                }
                            DragAndDropTransferData(
                                transferable =
                                    DragAndDropTransferable(
                                        java.awt.datatransfer.DataFlavor.javaFileListFlavor.let {
                                            object : java.awt.datatransfer.Transferable {
                                                private val flavors =
                                                    arrayOf(java.awt.datatransfer.DataFlavor.javaFileListFlavor)

                                                override fun getTransferDataFlavors() = flavors

                                                override fun isDataFlavorSupported(
                                                    f: java.awt.datatransfer.DataFlavor?,
                                                ) = f == java.awt.datatransfer.DataFlavor.javaFileListFlavor

                                                override fun getTransferData(
                                                    f: java.awt.datatransfer.DataFlavor?,
                                                ): Any =
                                                    if (f == java.awt.datatransfer.DataFlavor.javaFileListFlavor) {
                                                        listOf(tmp)
                                                    } else {
                                                        throw java.awt.datatransfer.UnsupportedFlavorException(f)
                                                    }
                                            }
                                        },
                                    ),
                                supportedActions = listOf(DragAndDropTransferAction.Copy),
                                onTransferCompleted = {
                                    onLog(
                                        "[DnD] file export action=$it path=${tmp.absolutePath}",
                                    )
                                },
                            )
                        },
                    ),
        )
        BasicText(
            text = "DROP HERE  (drops=$dropCount)",
            style = TextStyle(color = Color(0xFFE6E6E6), fontSize = 12.sp, fontWeight = FontWeight.Medium),
            modifier =
                Modifier
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
            text =
                "mgr=${diag.constructed.intValue}  qry=${diag.isRequiredQueries.intValue}  " +
                    "req=${diag.requests.intValue}  xfer=${diag.transfers.intValue}",
            style = TextStyle(color = Color(0xFF34D399), fontSize = 11.sp, fontWeight = FontWeight.Bold),
        )
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Suppress("CyclomaticComplexMethod")
private fun runApp() =
    nucleusApplication {
        val previewEvents = remember { mutableStateListOf<String>() }
        var childRequest by remember { mutableStateOf<Pair<Boolean, Boolean>?>(null) }

        val titleBarStyle =
            TitleBarStyle(
                colors =
                    TitleBarColors(
                        background = Color(0xFF1A1D24),
                        inactiveBackground = Color(0xFF15181D),
                        content = Color(0xFFE6E6E6),
                        border = Color.Transparent,
                    ),
                metrics = TitleBarMetrics(height = 36.dp),
            )

        val mainState =
            rememberWindowState(
                initialBoundsProvider =
                    WindowBoundsProvider(WindowSizeProvider.Fixed(DpSize(1024.dp, 720.dp))),
            )
        NucleusDecoratedWindowTheme(isDark = true, titleBarStyle = titleBarStyle) {
            DecoratedWindow(
                onCloseRequest = ::exitApplication,
                state = mainState,
                title = "Tao Backend Demo",
                minSize = DpSize(640.dp, 480.dp),
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
                nativeContextMenu = true,
            ) {
                val taoWindow = nucleusWindow.unsafe.taoWindow!!
                var clicks by remember { mutableStateOf(0) }
                val enabledBlobs = remember { mutableStateListOf(true, true, true, true) }
                // NUCLEUS_DEMO_TAB lets automation (the CI a11y probes) land
                // directly on a given tab, e.g. NUCLEUS_DEMO_TAB=A11y.
                var selectedTab by remember {
                    mutableStateOf(
                        Tab.entries.firstOrNull {
                            it.name.equals(System.getenv("NUCLEUS_DEMO_TAB"), ignoreCase = true)
                        } ?: Tab.Demo,
                    )
                }
                // NUCLEUS_DEMO_AUTOSWITCH_MS=<delay> cycles through every tab
                // (delay each) and lands on WebView — automation hook for the
                // mid-session NativeView mount path (start on Demo, render a
                // lot of text, then embed), which behaves differently from
                // landing on WebView directly.
                LaunchedEffect(Unit) {
                    val autoSwitchMs = System.getenv("NUCLEUS_DEMO_AUTOSWITCH_MS")?.toLongOrNull()
                    if (autoSwitchMs != null) {
                        for (tab in Tab.entries.filter { it != Tab.WebView }) {
                            kotlinx.coroutines.delay(autoSwitchMs)
                            selectedTab = tab
                        }
                        kotlinx.coroutines.delay(autoSwitchMs)
                        selectedTab = Tab.WebView
                    }
                }
                val events = previewEvents

                TitleBar(modifier = Modifier.macOSLargeCornerRadius()) { state ->
                    Row(
                        modifier = Modifier.align(Alignment.Start).padding(start = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Box(
                            modifier =
                                Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(if (state.isActive) Color(0xFF34D399) else Color(0xFF6B7280)),
                        )
                        BasicText(
                            text = if (state.isActive) "Live" else "Inactive",
                            style =
                                TextStyle(
                                    color = Color(0xFFA0A4B0),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Medium,
                                ),
                        )
                    }

                    BasicText(
                        text = title,
                        modifier = Modifier.align(Alignment.CenterHorizontally),
                        style =
                            TextStyle(
                                color = if (state.isActive) Color(0xFFE6E6E6) else Color(0xFFE6E6E6).copy(alpha = 0.5f),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                            ),
                    )

                    Row(
                        modifier = Modifier.align(Alignment.End).padding(end = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        PALETTE.forEachIndexed { idx, color ->
                            Box(
                                modifier =
                                    Modifier
                                        .size(14.dp)
                                        .clip(CircleShape)
                                        .background(if (enabledBlobs[idx]) color else color.copy(alpha = 0.18f))
                                        .border(
                                            1.dp,
                                            if (enabledBlobs[idx]) color.copy(alpha = 0.4f) else Color.Transparent,
                                            CircleShape,
                                        ).clickable { enabledBlobs[idx] = !enabledBlobs[idx] },
                            )
                        }
                        Box(modifier = Modifier.size(width = 8.dp, height = 16.dp))
                        BasicText(
                            text = "Clear",
                            style =
                                TextStyle(
                                    color = Color(0xFF8AB4FF),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                ),
                            modifier =
                                Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(Color.White.copy(alpha = 0.06f))
                                    .clickable {
                                        clicks = 0
                                        events.clear()
                                    }.padding(horizontal = 8.dp, vertical = 4.dp),
                        )
                    }
                }

                Column(modifier = Modifier.fillMaxSize().background(Color(0xFF0F1115))) {
                    DnDStage0Banner(onLog = { logEvent(events, it) })
                    TabBar(selectedTab, onSelect = { selectedTab = it })
                    Box(modifier = Modifier.weight(1f).fillMaxSize()) {
                        when (selectedTab) {
                            Tab.Demo ->
                                FancyDemo(
                                    modifier = Modifier.fillMaxSize(),
                                    clicks = clicks,
                                    onClick = {
                                        clicks++
                                        logEvent(events, "click @ demo (#$clicks)")
                                    },
                                    enabledBlobs = enabledBlobs,
                                )
                            Tab.Scroll -> ScrollTab(modifier = Modifier.fillMaxSize())
                            Tab.Zoom -> ZoomTab(modifier = Modifier.fillMaxSize())
                            Tab.Actions ->
                                ActionsTab(
                                    modifier = Modifier.fillMaxSize(),
                                    window = taoWindow,
                                    placement =
                                        if (mainState.isInitialized) {
                                            mainState.placement
                                        } else {
                                            WindowPlacement.Floating
                                        },
                                    onPlacementChange = { mainState.requestPlacement(it) },
                                    onLog = { logEvent(events, it) },
                                    onOpenChildWindow = { childEnabled, childFocusable ->
                                        childRequest = childEnabled to childFocusable
                                        logEvent(
                                            events,
                                            "openChildWindow(enabled=$childEnabled, focusable=$childFocusable)",
                                        )
                                    },
                                )
                            Tab.A11y -> A11yTab(modifier = Modifier.fillMaxSize())
                            Tab.Complex -> ComplexTab(modifier = Modifier.fillMaxSize())
                            Tab.Events -> EventsTab(modifier = Modifier.fillMaxSize(), events = events)
                            Tab.WebView -> WebViewTab(modifier = Modifier.fillMaxSize())
                            Tab.SwiftUI -> SwiftUITab(modifier = Modifier.fillMaxSize())
                            Tab.Texture -> TextureTab(modifier = Modifier.fillMaxSize())
                            Tab.Spellcheck -> SpellcheckTab(modifier = Modifier.fillMaxSize())
                        }
                    }
                }
            }

            childRequest?.let { (childEnabled, childFocusable) ->
                DecoratedWindow(
                    onCloseRequest = { childRequest = null },
                    state =
                        rememberWindowState(
                            initialBoundsProvider =
                                WindowBoundsProvider(WindowSizeProvider.Fixed(DpSize(480.dp, 240.dp))),
                        ),
                    title = "Child (enabled=$childEnabled, focusable=$childFocusable)",
                    enabled = childEnabled,
                    focusable = childFocusable,
                ) {
                    Box(
                        modifier = Modifier.fillMaxSize().background(Color(0xFF15171C)),
                        contentAlignment = Alignment.Center,
                    ) {
                        BasicText(
                            text =
                                buildString {
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
