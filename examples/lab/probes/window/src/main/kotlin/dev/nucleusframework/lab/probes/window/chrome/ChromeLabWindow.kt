package dev.nucleusframework.lab.probes.window.chrome

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.rememberWindowState
import dev.nucleusframework.application.NucleusApplicationScope
import dev.nucleusframework.core.runtime.Platform
import dev.nucleusframework.lab.designsystem.LabDecoratedWindow
import dev.nucleusframework.lab.designsystem.LabDimens
import dev.nucleusframework.lab.designsystem.LabShapes
import dev.nucleusframework.lab.designsystem.LabTheme
import dev.nucleusframework.lab.designsystem.LabTitleBar
import dev.nucleusframework.lab.designsystem.Readout
import dev.nucleusframework.lab.designsystem.Text
import dev.nucleusframework.window.DecoratedWindowScope
import dev.nucleusframework.window.LocalWindowChromeInsets
import dev.nucleusframework.window.TitleBarLayoutPolicy
import dev.nucleusframework.window.TitleBarPlacement
import dev.nucleusframework.window.WindowAppearance
import dev.nucleusframework.window.WindowAppearanceMode
import dev.nucleusframework.window.WindowBackground
import dev.nucleusframework.window.WindowControls
import dev.nucleusframework.window.WindowGlassRegionKind
import dev.nucleusframework.window.WindowScaffold
import dev.nucleusframework.window.WindowsBackdrop
import dev.nucleusframework.window.macOSLargeCornerRadius
import dev.nucleusframework.window.newFullscreenControls
import dev.nucleusframework.window.noWindowDrag
import dev.nucleusframework.window.windowDragArea
import dev.nucleusframework.window.windowGlassRegion

/** Corner of the sidebar: its clip and its glass region must agree. */
private val SidebarCorner = 8.dp

/** Height of the custom toolbar: taller than a stock bar, so the difference shows in the insets. */
private val ToolbarHeight = 48.dp

/**
 * The chrome under test: one window whose every chrome primitive follows [vm]'s config. The
 * primitives are design-system agnostic, so the window wears the Lab's own style (Jewel
 * window and title bar, Lab tokens for the toolbar, sidebar and drag area) in the forced
 * appearance.
 */
@Composable
internal fun NucleusApplicationScope.ChromeLabWindow(
    vm: ChromeViewModel,
    close: () -> Unit,
) {
    val state by vm.state.collectAsState()
    val config = state.config
    val dark =
        when (config.appearance) {
            AppearanceChoice.System -> LabTheme.isDark
            AppearanceChoice.Light -> false
            AppearanceChoice.Dark -> true
        }
    LabTheme(isDark = dark) {
        ChromeWindow(vm, config, dark, close)
    }
}

@Composable
private fun NucleusApplicationScope.ChromeWindow(
    vm: ChromeViewModel,
    config: ChromeConfig,
    dark: Boolean,
    close: () -> Unit,
) {
    LabDecoratedWindow(
        title = "Chrome lab",
        onCloseRequest = close,
        state = rememberWindowState(size = DpSize(960.dp, 620.dp)),
        minimumSize = DpSize(640.dp, 400.dp),
    ) {
        val tao = nucleusWindow.unsafe.taoWindow
        LaunchedEffect(tao) { tao?.let { vm.onIntent(ChromeIntent.Attached(it)) } }
        WindowBackground(LabTheme.colors.background)
        WindowAppearance(if (dark) WindowAppearanceMode.Dark else WindowAppearanceMode.Light)
        // A silent no-op off Windows; the probe says so in its capabilities.
        WindowsBackdrop(config.backdrop, config.tint.color, config.tier)
        WindowScaffold(
            modifier = Modifier.macOSLargeCornerRadius(config.largeCorner),
            titleBar = { ChromeBar(config) },
            titleBarPlacement =
                when (config.placement) {
                    BarPlacement.Docked -> TitleBarPlacement.Docked
                    BarPlacement.Overlay ->
                        TitleBarPlacement.Overlay(
                            config.autoHideInFullscreen,
                            config.passThroughToContent,
                        )
                },
        ) { padding ->
            ReportInsets(vm)
            Row(Modifier.fillMaxSize()) {
                Sidebar(config, padding)
                ChromeContent(vm, config, padding, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun ReportInsets(vm: ChromeViewModel) {
    val insets = LocalWindowChromeInsets.current
    val direction = LocalLayoutDirection.current
    val readback =
        ChromeReadback(
            titleBarHeightDp = insets.titleBarHeight.value,
            controlsInsets = insets.controlsInsets.describe(direction),
            layoutDirection = direction.name,
        )
    LaunchedEffect(readback) { vm.onIntent(ChromeIntent.Measured(readback)) }
}

private fun PaddingValues.describe(direction: LayoutDirection): String =
    "start ${calculateLeftPadding(direction).value} · top ${calculateTopPadding().value} · " +
        "end ${calculateRightPadding(direction).value} · bottom ${calculateBottomPadding().value} dp"

@Composable
private fun DecoratedWindowScope.ChromeBar(config: ChromeConfig) {
    val colors = LabTheme.colors
    when (config.bar) {
        BarKind.Stock ->
            LabTitleBar(
                modifier = Modifier.newFullscreenControls(config.newFullscreenControls),
                controlButtonsDirection = config.controlsDirection,
                layoutPolicy = if (config.fillCenter) TitleBarLayoutPolicy.FillCenter else TitleBarLayoutPolicy.Default,
            ) { _ ->
                Text(
                    "Chrome lab",
                    style = LabTheme.typography.heading,
                    modifier = Modifier.align(Alignment.Start).padding(horizontal = LabDimens.block),
                )
                Box(
                    Modifier
                        .align(Alignment.CenterHorizontally)
                        .height(26.dp)
                        .clip(LabShapes.block)
                        .background(colors.raised)
                        .padding(horizontal = LabDimens.gap),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Text(
                        if (config.fillCenter) "FillCenter: I span the free width" else "Default: I keep my size",
                        style = LabTheme.typography.small,
                        maxLines = 1,
                    )
                }
            }
        BarKind.Custom -> {
            val insets = LocalWindowChromeInsets.current
            val transparent = config.placement == BarPlacement.Overlay || config.backdropActive
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(ToolbarHeight)
                    .background(if (transparent) Color.Transparent else colors.panel)
                    .windowDragArea(),
            ) {
                Text(
                    "Custom toolbar · drag me, double-click me",
                    style = LabTheme.typography.heading,
                    modifier =
                        Modifier
                            .align(Alignment.CenterStart)
                            .padding(insets.controlsInsets)
                            .padding(horizontal = LabDimens.block),
                )
                // macOS draws real traffic lights; everywhere else the app owes the user its controls.
                if (Platform.Current != Platform.MacOS) {
                    WindowControls(Modifier.align(Alignment.CenterEnd).fillMaxHeight())
                }
            }
        }
    }
}

@Composable
private fun Sidebar(
    config: ChromeConfig,
    padding: PaddingValues,
) {
    val glass = config.glassSidebar && Platform.Current == Platform.MacOS
    Column(
        Modifier
            .width(220.dp)
            .fillMaxHeight()
            .padding(padding)
            .padding(LabDimens.gap)
            .clip(RoundedCornerShape(SidebarCorner))
            .then(if (glass) Modifier.windowGlassRegion(WindowGlassRegionKind.Sidebar, SidebarCorner) else Modifier)
            .then(if (!glass && !config.backdropActive) Modifier.background(LabTheme.colors.panel) else Modifier)
            .padding(LabDimens.block),
        verticalArrangement = Arrangement.spacedBy(LabDimens.gap),
    ) {
        Text("Sidebar", style = LabTheme.typography.heading)
        Text(
            when {
                glass -> "system material (windowGlassRegion)"
                config.backdropActive -> "unpainted: the Windows backdrop shows"
                else -> "painted surface"
            },
            style = LabTheme.typography.small,
            color = LabTheme.colors.textMuted,
        )
    }
}

@Composable
private fun ChromeContent(
    vm: ChromeViewModel,
    config: ChromeConfig,
    padding: PaddingValues,
    modifier: Modifier,
) {
    val state by vm.state.collectAsState()
    val colors = LabTheme.colors
    val surface = if (config.backdropActive) Color.Transparent else colors.background
    Column(
        modifier
            .fillMaxHeight()
            .background(surface)
            .padding(padding)
            .padding(LabDimens.page),
        verticalArrangement = Arrangement.spacedBy(LabDimens.gap),
    ) {
        Text("Content", style = LabTheme.typography.heading)
        Readout("Bar height", state.readback?.let { "${it.titleBarHeightDp} dp" })
        Readout("Controls insets", state.readback?.controlsInsets)
        Readout("Window", state.snapshot?.describe())
        Box(
            Modifier
                .fillMaxWidth()
                .height(120.dp)
                .clip(LabShapes.specimen)
                .background(if (config.dragFromContent) colors.selection else colors.target)
                .then(if (config.dragFromContent) Modifier.windowDragArea() else Modifier.noWindowDrag())
                .onPointerEvent(PointerEventType.Press) {
                    if (config.dragFromContent) vm.onIntent(ChromeIntent.ContentDragPressed)
                },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                if (config.dragFromContent) {
                    "Drag area: pressing here moves the window"
                } else {
                    "Plain content: pressing here does nothing to the window"
                },
                color = if (config.dragFromContent) colors.text else colors.textMuted,
            )
        }
    }
}
