package screencapturedemo

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState
import dev.nucleusframework.application.DecoratedWindow
import dev.nucleusframework.application.nucleusApplication
import dev.nucleusframework.core.runtime.Platform
import dev.nucleusframework.screencapture.CaptureRegion
import dev.nucleusframework.screencapture.CaptureWindow
import dev.nucleusframework.screencapture.ScreenCapture
import dev.nucleusframework.screencapture.ScreenCaptureException
import dev.nucleusframework.screencapture.ScreenImage
import dev.nucleusframework.window.NucleusDecoratedWindowTheme
import dev.nucleusframework.window.TitleBar
import dev.nucleusframework.window.tao.LocalTaoWindow
import dev.nucleusframework.window.tao.TaoWindow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo
import java.io.File
import kotlin.system.exitProcess

private val selfTest = System.getenv("SCREEN_CAPTURE_DEMO_SELFTEST") == "1"

fun main(args: Array<String>) =
    nucleusApplication(args, enableSingleInstance = false) {
        var coverVisible by remember { mutableStateOf(false) }
        NucleusDecoratedWindowTheme(isDark = true) {
            DecoratedWindow(
                onCloseRequest = ::exitApplication,
                title = WINDOW_TITLE,
                // Self-test: nothing else may cover the pattern (the cover window, shown later, goes above).
                alwaysOnTop = selfTest,
                state = rememberWindowState(size = DpSize(760.dp, 620.dp), position = WindowPosition(120.dp, 120.dp)),
            ) {
                TitleBar { BasicText(WINDOW_TITLE, style = TextStyle(color = Color.White)) }
                val window = LocalTaoWindow.current
                if (window != null && selfTest) {
                    LaunchedEffect(window) {
                        val test =
                            SelfTest(
                                window = window,
                                windowId = withContext(Dispatchers.IO) { awaitWindowId(window) },
                                showCover = { visible -> withContext(Dispatchers.Main) { coverVisible = visible } },
                                setMinimized = { minimized ->
                                    withContext(Dispatchers.Main) { window.setMinimized(minimized) }
                                },
                            )
                        val failures = withContext(Dispatchers.IO) { test.run() }
                        exitProcess(failures.coerceAtMost(100))
                    }
                }
                DemoContent(window)
            }
            if (coverVisible) {
                // Covers the demo window entirely, above it, for the occluded-window check.
                DecoratedWindow(
                    onCloseRequest = { coverVisible = false },
                    title = "Cover",
                    alwaysOnTop = true,
                    focusable = false,
                    state = rememberWindowState(size = DpSize(860.dp, 720.dp), position = WindowPosition(70.dp, 70.dp)),
                ) {
                    Box(Modifier.fillMaxSize().background(Color(0xFFFF00FF)))
                }
            }
        }
    }

private const val WINDOW_TITLE = "Screen Capture Demo"

/**
 * The id [ScreenCapture.captureWindow] takes for a Tao window; `0` where it has none. Blocking.
 *
 * macOS: Tao does not expose the NSWindow's CGWindowID yet, so the window is found in
 * [ScreenCapture.windows] by pid and title.
 */
private fun windowIdOf(window: TaoWindow): Long =
    when (Platform.Current) {
        Platform.Windows -> window.nativeHandle
        Platform.Linux -> window.x11WindowId ?: 0L
        Platform.MacOS ->
            ScreenCapture
                .windows()
                .firstOrNull { it.pid == ProcessHandle.current().pid() && it.title == WINDOW_TITLE }
                ?.id ?: 0L
        else -> 0L
    }

/** [windowIdOf], waiting for the window to reach the screen. */
private suspend fun awaitWindowId(window: TaoWindow): Long {
    repeat(50) {
        val id = runCatching { windowIdOf(window) }.getOrDefault(0L)
        if (id != 0L) return id
        kotlinx.coroutines.delay(100)
    }
    return 0L
}

@Composable
private fun DemoContent(window: TaoWindow?) {
    val scope = rememberCoroutineScope()
    var preview by remember { mutableStateOf<ImageBitmap?>(null) }
    var last by remember { mutableStateOf<ScreenImage?>(null) }
    var windows by remember { mutableStateOf<List<CaptureWindow>>(emptyList()) }
    var status by remember {
        mutableStateOf("backend=${ScreenCapture.backend} permission=${ScreenCapture.permissionStatus()}")
    }
    val displays = remember { runCatching { ScreenCapture.displays() }.getOrDefault(emptyList()) }

    fun capture(
        label: String,
        block: () -> ScreenImage,
    ) {
        scope.launch {
            status = "Capturing $label…"
            val started = System.nanoTime()
            val outcome = withContext(Dispatchers.IO) { runCatching(block) }
            val ms = (System.nanoTime() - started) / 1_000_000
            outcome
                .onSuccess {
                    last = it
                    preview = it.toImageBitmap()
                    status = "$label: ${it.width}x${it.height} in ${ms}ms"
                }.onFailure {
                    val reason = if (it is ScreenCaptureException) "${it.failure} — ${it.message}" else "$it"
                    status = "$label failed: $reason"
                }
        }
    }

    Column(
        Modifier.fillMaxSize().background(Color(0xFF202124)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        TargetPattern.Content()
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (display in displays) {
                DemoButton("${display.name} (${display.widthPx}x${display.heightPx})") {
                    capture(display.name) { ScreenCapture.captureDisplay(display, includeCursor = true) }
                }
            }
            displays.firstOrNull()?.let { primary ->
                DemoButton("Region 400x300") {
                    capture("region") { ScreenCapture.captureDisplay(primary, CaptureRegion(0, 0, 400, 300)) }
                }
            }
            if (window != null && ScreenCapture.isWindowCaptureSupported) {
                DemoButton("This window") { capture("window") { ScreenCapture.captureWindow(windowIdOf(window)) } }
                DemoButton("List windows") {
                    scope.launch {
                        windows =
                            withContext(
                                Dispatchers.IO,
                            ) { runCatching { ScreenCapture.windows() } }.getOrDefault(emptyList())
                    }
                }
            }
            for (target in windows.take(12)) {
                val label = listOf(target.appName, target.title).filter { it.isNotEmpty() }.joinToString(" — ")
                DemoButton(label.take(40).ifEmpty { "#${target.id}" }) {
                    capture(label) { ScreenCapture.captureWindow(target.id) }
                }
            }
            last?.let { image ->
                DemoButton("Save PNG") {
                    scope.launch {
                        val file =
                            File(
                                System.getProperty("java.io.tmpdir"),
                                "screen-capture-${System.currentTimeMillis()}.png",
                            )
                        withContext(Dispatchers.IO) { file.writeBytes(image.toPng()) }
                        status = "Saved ${file.absolutePath}"
                    }
                }
            }
        }
        BasicText(status, style = TextStyle(color = Color.White, fontSize = 13.sp))
        Box(Modifier.fillMaxWidth().weight(1f).border(1.dp, Color.Gray)) {
            preview?.let {
                Image(it, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            }
        }
    }
}

@Composable
private fun DemoButton(
    label: String,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .background(
                Color(0xFF3C4043),
            ).clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        BasicText(label, style = TextStyle(color = Color.White, fontSize = 13.sp))
    }
}

private fun ScreenImage.toImageBitmap(): ImageBitmap =
    org.jetbrains.skia.Image
        .makeRaster(ImageInfo(width, height, ColorType.BGRA_8888, ColorAlphaType.OPAQUE), toBgraBytes(), width * 4)
        .toComposeImageBitmap()
