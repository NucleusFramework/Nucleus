package com.example.demo

import androidx.compose.foundation.background
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogState
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.example.demo.gallery.GalleryScreen
import com.example.demo.icons.MaterialIconsDark_mode
import com.example.demo.icons.MaterialIconsInfo
import com.example.demo.icons.MaterialIconsLight_mode
import com.example.demo.icons.RadixEnterFullScreen
import com.example.demo.icons.RadixExitFullScreen
import com.example.demo.icons.TablerCoffee
import com.example.demo.icons.TablerCoffeeOff
import com.example.demo.icons.TablerTextDirectionLtr
import com.example.demo.icons.TablerTextDirectionRtl
import com.example.demo.icons.VscodeCodiconsColorMode
import com.materialkolor.DynamicMaterialTheme
import com.materialkolor.PaletteStyle
import io.github.kdroidfilter.nucleus.aot.runtime.AotRuntime
import io.github.kdroidfilter.nucleus.autolaunch.AutoLaunch
import io.github.kdroidfilter.nucleus.core.runtime.DeepLinkHandler
import io.github.kdroidfilter.nucleus.core.runtime.NucleusApp
import io.github.kdroidfilter.nucleus.core.runtime.Platform
import io.github.kdroidfilter.nucleus.core.runtime.SingleInstanceManager
import io.github.kdroidfilter.nucleus.darkmodedetector.isSystemInDarkMode
import io.github.kdroidfilter.nucleus.energymanager.EnergyManager
import io.github.kdroidfilter.nucleus.graalvm.GraalVmInitializer
import io.github.kdroidfilter.nucleus.launcher.windows.WindowsJumpListManager
import io.github.kdroidfilter.nucleus.nativehttp.NativeHttpClient
import io.github.kdroidfilter.nucleus.systemcolor.systemAccentColor
import io.github.kdroidfilter.nucleus.updater.NucleusUpdater
import io.github.kdroidfilter.nucleus.updater.UpdateEvent
import io.github.kdroidfilter.nucleus.updater.UpdateLevel
import io.github.kdroidfilter.nucleus.updater.UpdateResult
import io.github.kdroidfilter.nucleus.updater.provider.GitHubProvider
import io.github.kdroidfilter.nucleus.window.macOSLargeCornerRadius
import io.github.kdroidfilter.nucleus.window.material.MaterialDecoratedDialog
import io.github.kdroidfilter.nucleus.window.material.MaterialDecoratedWindow
import io.github.kdroidfilter.nucleus.window.material.MaterialDialogTitleBar
import io.github.kdroidfilter.nucleus.window.material.MaterialTitleBar
import io.github.kdroidfilter.nucleus.window.newFullscreenControls
import java.awt.event.WindowEvent
import java.awt.event.WindowFocusListener
import java.io.File
import java.net.URI
import kotlin.system.exitProcess

private const val AOT_TRAINING_DURATION_MS = 45_000L

private val deepLinkUri = mutableStateOf<URI?>(null)

// macOS resolves the AppleEvent only after NSApp.run starts (i.e. after AWT
// init), so the early call from main() returns false. We stash args here and
// re-query from Compose to pick up the late signal.
private var nucleusMainArgs: Array<String> = emptyArray()

@Suppress("LongMethod", "CyclomaticComplexMethod")
fun main(args: Array<String>) {
    GraalVmInitializer.initialize()

    nucleusMainArgs = args
    AutoLaunch.wasStartedAtLogin(args) // prime the cache for Win32 / MSIX
    MacLaunchDiagnostic.capture(args)

    // Set AUMID before any window is created (required for jump lists in non-APPX mode)
    if (Platform.Current == Platform.Windows) {
        WindowsJumpListManager.setProcessAppId()
    }

    DeepLinkHandler.register(args) { uri ->
        println("[JumpList/DeepLink] Received: $uri")
        deepLinkUri.value = uri
    }

    // Stop app after 15 seconds during AOT training mode
    // Use -Dnucleus.aot.mode=training to test
    if (AotRuntime.isTraining()) {
        println("[AOT] Training mode - will exit in 15 seconds")

        Thread({
            Thread.sleep(AOT_TRAINING_DURATION_MS)
            println("[AOT] Time's up, exiting...")
            exitProcess(0)
        }, "aot-timer").apply {
            isDaemon = false
            start()
        }
    }

    application {
        var isWindowVisible by remember { mutableStateOf(true) }
        var isFillCenterWindowVisible by remember { mutableStateOf(false) }
        var restoreRequestCount by remember { mutableStateOf(0) }
        var themeMode by remember { mutableStateOf(ThemeMode.System) }
        var showInfoDialog by remember { mutableStateOf(false) }

        val isFirstInstance =
            remember {
                SingleInstanceManager.isSingleInstance(
                    onRestoreFileCreated = { DeepLinkHandler.writeUriTo(this) },
                    onRestoreRequest = {
                        DeepLinkHandler.readUriFrom(this)
                        isWindowVisible = true
                        restoreRequestCount++
                    },
                )
            }

        if (!isFirstInstance) {
            exitApplication()
            return@application
        }

        if (isWindowVisible) {
            val isDark =
                when (themeMode) {
                    ThemeMode.System -> isSystemInDarkMode()
                    ThemeMode.Dark -> true
                    ThemeMode.Light -> false
                }
            val accentColor = systemAccentColor()
            val seedColor = accentColor ?: Color.Yellow

            var isRtl by remember { mutableStateOf(false) }

            DynamicMaterialTheme(
                seedColor = seedColor,
                isDark = isDark,
                animate = true,
                style = PaletteStyle.TonalSpot,
            ) {
                val state =
                    rememberWindowState(
                        position = WindowPosition.Aligned(Alignment.Center),
                        placement = WindowPlacement.Floating,
                    )
                MaterialDecoratedWindow(
                    state = state,
                    onCloseRequest = ::exitApplication,
                    title = "Nucleus Demo",
                    minimumSize = DpSize(1200.dp, 480.dp),
                ) {
                    CompositionLocalProvider(
                        LocalLayoutDirection provides if (isRtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
                    ) {
                        val tabs =
                            buildList {
                                addAll(listOf("Nucleus", "Fill Title", "Gallery", "Taskbar"))
                                add("Notifications (Common)")
                                if (Platform.Current == Platform.MacOS ||
                                    Platform.Current == Platform.Linux ||
                                    Platform.Current == Platform.Windows
                                ) {
                                    add("Notifications")
                                }
                                if (Platform.Current == Platform.Windows ||
                                    Platform.Current == Platform.Linux ||
                                    Platform.Current == Platform.MacOS
                                ) {
                                    add("Launcher")
                                }
                                add("Media Control")
                                add("Auto-Launch")
                                add("Hotkeys")
                                if (Platform.Current == Platform.MacOS) {
                                    add("Menu")
                                }
                            }
                        var selectedTab by remember { mutableStateOf("Nucleus") }

                        MaterialTitleBar(modifier = Modifier.newFullscreenControls().macOSLargeCornerRadius()) { _ ->
                            val titleBarAlignment =
                                if (Platform.Current == Platform.MacOS) Alignment.End else Alignment.Start

                            TitleBarIconButton(
                                imageVector =
                                    when (themeMode) {
                                        ThemeMode.System -> VscodeCodiconsColorMode
                                        ThemeMode.Dark -> MaterialIconsDark_mode
                                        ThemeMode.Light -> MaterialIconsLight_mode
                                    },
                                contentDescription = "Toggle theme",
                                modifier = Modifier.align(titleBarAlignment),
                                onClick = { themeMode = themeMode.next() },
                            )
                            TitleBarIconButton(
                                imageVector = MaterialIconsInfo,
                                contentDescription = "System info",
                                modifier = Modifier.align(titleBarAlignment),
                                onClick = { showInfoDialog = true },
                            )

                            var caffeineActive by remember {
                                mutableStateOf(EnergyManager.isScreenAwakeActive())
                            }
                            TitleBarIconButton(
                                imageVector = if (caffeineActive) TablerCoffee else TablerCoffeeOff,
                                contentDescription = if (caffeineActive) "Disable caffeine" else "Enable caffeine",
                                modifier = Modifier.align(titleBarAlignment),
                                onClick = {
                                    if (caffeineActive) {
                                        EnergyManager.releaseScreenAwake()
                                    } else {
                                        EnergyManager.keepScreenAwake()
                                    }
                                    caffeineActive = EnergyManager.isScreenAwakeActive()
                                },
                            )
                            val isFullscreen = state.placement == WindowPlacement.Fullscreen
                            TitleBarIconButton(
                                imageVector = if (isFullscreen) RadixExitFullScreen else RadixEnterFullScreen,
                                contentDescription = if (isFullscreen) "Exit fullscreen" else "Enter fullscreen",
                                modifier = Modifier.align(titleBarAlignment),
                                onClick = {
                                    if (isFullscreen) {
                                        // NativeFullscreenWindowState restores the previous placement internally.
                                        // Setting any non-Fullscreen value on the delegate triggers the exit.
                                        state.placement = WindowPlacement.Floating
                                    } else {
                                        state.placement = WindowPlacement.Fullscreen
                                    }
                                },
                            )
                            TitleBarIconButton(
                                imageVector = if (isRtl) TablerTextDirectionRtl else TablerTextDirectionLtr,
                                contentDescription = if (isRtl) "Switch to LTR" else "Switch to RTL",
                                modifier = Modifier.align(titleBarAlignment),
                                onClick = { isRtl = !isRtl },
                            )
                            DraggableTabs(
                                tabs = tabs,
                                selectedTab = selectedTab,
                                onSelect = { selectedTab = it },
                                onReorder = { _, _ -> },
                                modifier = Modifier.align(Alignment.CenterHorizontally),
                            )
                        }
                        LaunchedEffect(restoreRequestCount) {
                            if (restoreRequestCount > 0) {
                                window.toFront()
                                window.requestFocus()
                            }
                        }

                        // Energy efficiency: full when minimized, light when unfocused
                        var isWindowFocused by remember { mutableStateOf(window.isFocused) }
                        DisposableEffect(window) {
                            val listener =
                                object : WindowFocusListener {
                                    override fun windowGainedFocus(e: WindowEvent?) {
                                        isWindowFocused = true
                                    }

                                    override fun windowLostFocus(e: WindowEvent?) {
                                        isWindowFocused = false
                                    }
                                }
                            window.addWindowFocusListener(listener)
                            onDispose { window.removeWindowFocusListener(listener) }
                        }
                        LaunchedEffect(state.isMinimized, isWindowFocused) {
                            when {
                                state.isMinimized -> {
                                    EnergyManager.disableLightEfficiencyMode()
                                    EnergyManager.enableEfficiencyMode()
                                }
                                !isWindowFocused -> {
                                    EnergyManager.disableEfficiencyMode()
                                    EnergyManager.enableLightEfficiencyMode()
                                }
                                else -> {
                                    EnergyManager.disableEfficiencyMode()
                                    EnergyManager.disableLightEfficiencyMode()
                                }
                            }
                        }

                        when (selectedTab) {
                            "Nucleus" -> NucleusContent()
                            "Fill Title" ->
                                FillCenterDemoEntryTab(
                                    onOpenDemo = { isFillCenterWindowVisible = true },
                                )
                            "Notifications (Common)" -> CommonNotificationsScreen()
                            "Gallery" -> {
                                val currentDensity = LocalDensity.current
                                CompositionLocalProvider(
                                    LocalDensity provides
                                        Density(
                                            density = currentDensity.density * 0.75f,
                                            fontScale = currentDensity.fontScale,
                                        ),
                                ) {
                                    GalleryScreen(seedColor = seedColor)
                                }
                            }
                            "Taskbar" -> TaskbarProgressScreen(window)
                            "Notifications" -> {
                                when (Platform.Current) {
                                    Platform.MacOS -> NotificationsScreen()
                                    Platform.Linux -> LinuxNotificationsScreen()
                                    Platform.Windows -> WindowsNotificationsScreen()
                                    else -> {}
                                }
                            }
                            "Launcher" -> {
                                when (Platform.Current) {
                                    Platform.Windows -> WindowsLauncherScreen(window)
                                    Platform.MacOS -> MacOsLauncherScreen()
                                    Platform.Linux -> LauncherScreen()
                                    else -> {}
                                }
                            }
                            "Media Control" -> MediaControlScreen()
                            "Auto-Launch" -> AutoLaunchScreen()
                            "Hotkeys" -> GlobalHotKeyScreen()
                            "Menu" -> MacOsMenuScreen()
                        }

                        if (showInfoDialog) {
                            MaterialDecoratedDialog(
                                onCloseRequest = { showInfoDialog = false },
                                state = DialogState(size = DpSize(400.dp, 350.dp)),
                                title = "System Info",
                            ) {
                                MaterialDialogTitleBar { _ ->
                                    Text(
                                        title,
                                        modifier = Modifier.align(Alignment.CenterHorizontally),
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                }
                                Surface(modifier = Modifier.fillMaxSize()) {
                                    Column(
                                        modifier = Modifier.fillMaxSize().padding(24.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.Center,
                                    ) {
                                        Text("App ID: ${NucleusApp.appId}")
                                        NucleusApp.version?.let { Text("App Version: $it") }
                                        NucleusApp.vendor?.let { Text("Vendor: $it") }
                                        Spacer(Modifier.height(12.dp))
                                        Text("OS: ${System.getProperty("os.name")} ${System.getProperty("os.arch")}")
                                        Text(
                                            "Java: ${System.getProperty("java.version")}" +
                                                " (${System.getProperty("java.vendor")})",
                                        )
                                        Text("Runtime: ${System.getProperty("java.runtime.name", "Unknown")}")
                                    }
                                }
                            }
                        }
                    }
                }

                FillCenterDemoWindow(
                    visible = isFillCenterWindowVisible,
                    onCloseRequest = { isFillCenterWindowVisible = false },
                    seedColor = seedColor,
                )
            }
        }
    }
}

@Composable
internal fun NucleusContent() {
    val currentDeepLink by deepLinkUri
    // macOS: the kAEOpenApplication AppleEvent is delivered after NSApp.run()
    // starts processing — which is exactly when this composable first runs.
    // Reading the flag here is the JVM equivalent of checking in Cocoa's
    // applicationDidFinishLaunching delegate.
    val startedAtLogin = remember { AutoLaunch.wasStartedAtLogin(nucleusMainArgs) }
    val updater =
        remember {
            NucleusUpdater {
                provider = GitHubProvider(owner = "kdroidfilter", repo = "Nucleus")
                httpClient = NativeHttpClient.create()
            }
        }

    var updateEvent by remember { mutableStateOf(updater.consumeUpdateEvent()) }
    var updateStatus by remember { mutableStateOf("Checking for updates...") }
    var downloadProgress by remember { mutableStateOf(-1.0) }
    var downloadedFile by remember { mutableStateOf<File?>(null) }

    LaunchedEffect(Unit) {
        when (val result = updater.checkForUpdates()) {
            is UpdateResult.Available -> {
                val levelLabel =
                    when (result.level) {
                        UpdateLevel.MAJOR -> "Major"
                        UpdateLevel.MINOR -> "Minor"
                        UpdateLevel.PATCH -> "Patch"
                        UpdateLevel.PRE_RELEASE -> "Pre-release"
                    }
                updateStatus = "$levelLabel update available: v${result.info.version}"
                updater.downloadUpdate(result.info).collect { progress ->
                    downloadProgress = progress.percent
                    if (progress.file != null) {
                        downloadedFile = progress.file
                        updateStatus = "Download complete: v${result.info.version}"
                    }
                }
            }

            is UpdateResult.NotAvailable -> {
                updateStatus = "Up to date (v${updater.currentVersion})"
            }

            is UpdateResult.Error -> {
                updateStatus = "Update check failed: ${result.exception.message}"
            }
        }
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier.fillMaxSize().padding(32.dp),
        ) {
            Column(
                modifier = Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                NucleusAtom(atomSize = 200.dp)

                if (startedAtLogin) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        shape = MaterialTheme.shapes.medium,
                    ) {
                        Text(
                            text = "Started automatically at login",
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                }

                if (currentDeepLink != null) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "Deep Link",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = currentDeepLink.toString(),
                        textAlign = TextAlign.Center,
                    )
                }
            }

            if (updateEvent != null) {
                UpdateBanner(
                    event = updateEvent!!,
                    onDismiss = { updateEvent = null },
                    modifier = Modifier.align(Alignment.TopCenter),
                )
            }

            Column(
                modifier = Modifier.align(Alignment.BottomCenter),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (updater.isUpdateSupported()) {
                    Text(
                        text = "Auto-Update",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(updateStatus)

                    if (downloadProgress in 0.0..99.9) {
                        Spacer(modifier = Modifier.height(8.dp))
                        LinearProgressIndicator(
                            progress = { (downloadProgress / 100.0).toFloat() },
                            modifier = Modifier.fillMaxWidth(0.6f),
                        )
                        Text("${downloadProgress.toInt()}%")
                    }

                    if (downloadedFile != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(onClick = { updater.installAndRestart(downloadedFile!!) }) {
                            Text("Install & Restart")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FillCenterDemoEntryTab(onOpenDemo: () -> Unit) {
    Surface(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Button(onClick = onOpenDemo) {
                Text("Open Fill Title Window")
            }
        }
    }
}

@Composable
private fun UpdateBanner(
    event: UpdateEvent,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(0.6f).padding(top = 8.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "Updated from v${event.previousVersion} to v${event.newVersion}",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "${event.updateLevel.name.lowercase()
                    .replaceFirstChar { it.uppercase() }} update applied successfully",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Button(onClick = onDismiss) {
                Text("Dismiss")
            }
        }
    }
}

private enum class ThemeMode {
    System,
    Dark,
    Light,
    ;

    fun next(): ThemeMode =
        when (this) {
            System -> Dark
            Dark -> Light
            Light -> System
        }
}

@OptIn(ExperimentalMaterial3Api::class)
@Suppress("FunctionNaming", "DEPRECATION")
@Composable
private fun io.github.kdroidfilter.nucleus.window.TitleBarScope.TitleBarIconButton(
    imageVector: ImageVector,
    contentDescription: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val hoverInteraction = remember { MutableInteractionSource() }
    val isHovered by hoverInteraction.collectIsHoveredAsState()

    Box(modifier = modifier) {
        TooltipBox(
            positionProvider = TooltipDefaults.rememberTooltipPositionProvider(),
            tooltip = { PlainTooltip { Text(contentDescription) } },
            state = rememberTooltipState(),
        ) {
            Icon(
                imageVector = imageVector,
                contentDescription = contentDescription,
                tint = MaterialTheme.colorScheme.onSurface,
                modifier =
                    Modifier
                        .padding(horizontal = 4.dp)
                        .clip(CircleShape)
                        .hoverable(hoverInteraction)
                        .background(
                            if (isHovered) {
                                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)
                            } else {
                                Color.Transparent
                            },
                        ).titleBarClickable { onClick() }
                        .padding(4.dp)
                        .size(16.dp),
            )
        }
    }
}
