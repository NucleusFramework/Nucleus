package hotupdatedemo

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState
import dev.nucleusframework.application.DecoratedWindow
import dev.nucleusframework.application.nucleusApplication
import dev.nucleusframework.updater.NucleusUpdater
import dev.nucleusframework.updater.UpdateResult
import dev.nucleusframework.updater.provider.GenericProvider
import dev.nucleusframework.window.NucleusDecoratedWindowTheme
import dev.nucleusframework.window.TitleBar
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.last
import java.io.File
import java.time.LocalTime
import kotlin.time.Duration.Companion.seconds

private val feed: String? = System.getenv("HOT_UPDATE_DEMO_FEED")

// The E2E samples the screen at the window: keep it above whatever else is open there.
private val topmost = System.getenv("HOT_UPDATE_DEMO_TOPMOST") == "1"

// Multi-instance E2E: every launch is its own instance, holding the "document" passed as argument.
private val multiInstance = System.getenv("HOT_UPDATE_DEMO_MULTI") == "1"

// An instance that never checks the feed learns about an update another one installed.
private val checksForUpdates = System.getenv("HOT_UPDATE_DEMO_CHECK") != "0"
private val logFile = File(System.getProperty("java.io.tmpdir"), "hot-update-demo.log")

private fun log(message: String) {
    val line = "${LocalTime.now()} pid=${ProcessHandle.current().pid()} $message"
    runCatching { logFile.appendText("$line\n") }
}

fun main(args: Array<String>) =
    nucleusApplication(args, enableSingleInstance = !multiInstance) {
        val updater = remember { feed?.let { url -> NucleusUpdater { provider = GenericProvider(url) } } }
        val version = updater?.currentVersion ?: "dev"
        var status by remember { mutableStateOf(if (updater == null) "No update feed" else "Checking…") }

        LaunchedEffect(Unit) {
            val command =
                ProcessHandle
                    .current()
                    .info()
                    .command()
                    .orElse("?")
            log(
                "started version=$version args=${args.toList()} command=$command " +
                    "java.home=${System.getProperty("java.home")}",
            )
            updater?.consumeUpdateEvent()?.let { log("updated from ${it.previousVersion} to ${it.newVersion}") }
            if (updater == null || !checksForUpdates) return@LaunchedEffect
            // Poll, so that a chained E2E can publish the next version once this one is running.
            var result = updater.checkForUpdates()
            while (result !is UpdateResult.Available) {
                status = "Up to date"
                log("no update ($result)")
                delay(3.seconds)
                result = updater.checkForUpdates()
            }
            status = "Downloading ${result.info.version}…"
            val file = updater.downloadUpdate(result.info).last().file ?: return@LaunchedEffect
            status = "Installing ${result.info.version}…"
            log("installAndRestart ${file.name}")
            updater.installAndRestart(file, relaunchArguments = args.toList())
        }

        // Another instance installed an update: restart onto it, keeping this instance's document.
        // A real app would offer "Restart to update" instead of restarting on its own.
        LaunchedEffect(Unit) {
            val pending = updater?.pendingRestartVersion?.first { it != null } ?: return@LaunchedEffect
            log("pendingRestart $pending")
            status = "Restarting to $pending…"
            updater.restartToInstalledVersion(relaunchArguments = args.toList())
        }

        NucleusDecoratedWindowTheme(isDark = true) {
            DecoratedWindow(
                onCloseRequest = ::exitApplication,
                title = "Hot Update Demo $version",
                alwaysOnTop = topmost,
                state = rememberWindowState(size = DpSize(640.dp, 400.dp), position = WindowPosition(200.dp, 200.dp)),
            ) {
                TitleBar { BasicText("Hot Update Demo $version", style = TextStyle(color = Color.White)) }
                Column(
                    modifier = Modifier.fillMaxSize().background(versionColor(version)),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    BasicText(version, style = TextStyle(color = Color.White, fontSize = 72.sp))
                    BasicText(status, style = TextStyle(color = Color.White, fontSize = 20.sp))
                }
            }
        }
    }

private fun versionColor(version: String): Color =
    listOf(Color(0xFF1565C0), Color(0xFF2E7D32), Color(0xFF6A1B9A), Color(0xFFC62828))[
        Math.floorMod(version.hashCode(), 4),
    ]
