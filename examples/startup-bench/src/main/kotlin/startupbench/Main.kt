package startupbench

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.rememberWindowState
import dev.nucleusframework.application.DecoratedWindow
import dev.nucleusframework.application.aotTraining
import dev.nucleusframework.application.nucleusApplication
import dev.nucleusframework.window.TitleBar
import java.io.File
import kotlin.time.Duration.Companion.seconds

/**
 * Minimal Nucleus window used as the Hello World fixture for the startup /
 * memory protocol in PROTOCOL.md. One decorated window, one label, no extra
 * feature modules.
 *
 * Set `NUCLEUS_STARTUP_PROBE_DIR` (or `-Dnucleus.startup.probe.dir`) and the
 * in-framework probe writes `started.json` / `ready.json` / `settled.json`.
 * Set `NUCLEUS_STARTUP_WORKLOAD=search` to run the in-process inverted-index
 * workload after the first presented frame.
 */
fun main(args: Array<String>) =
    nucleusApplication(args = args, enableSingleInstance = false) {
        aotTraining(duration = 8.seconds)

        DecoratedWindow(
            onCloseRequest = ::exitApplication,
            state = rememberWindowState(size = DpSize(480.dp, 320.dp)),
            title = "Nucleus Startup Bench",
            minimumSize = DpSize(400.dp, 240.dp),
        ) {
            TitleBar { BasicText("Nucleus Startup Bench") }
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                BasicText("Hello World")
            }
            SearchWorkloadEffect()
        }
    }

@Suppress("FunctionNaming")
@Composable
private fun SearchWorkloadEffect() {
    val workload = System.getProperty("nucleus.startup.workload")?.trim().orEmpty()
    if (workload != "search") return
    val dir = System.getProperty("nucleus.startup.probe.dir")?.trim().orEmpty()
    if (dir.isEmpty()) return
    LaunchedEffect(Unit) {
        withFrameNanos { }
        val json = SearchWorkload.run().toJson()
        File(dir, "workload.json").writeText(json)
    }
}
