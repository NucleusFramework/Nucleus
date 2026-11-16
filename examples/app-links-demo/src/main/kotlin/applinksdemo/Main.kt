package applinksdemo

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import dev.nucleusframework.application.DecoratedWindow
import dev.nucleusframework.application.nucleusApplication
import dev.nucleusframework.window.NucleusDecoratedWindowTheme
import dev.nucleusframework.window.TitleBar
import java.io.File
import java.net.URI

// Outside AppData on purpose: an MSIX redirects its AppData writes into the package's own folder.
private val logFile =
    File(System.getenv("APP_LINKS_DEMO_LOG") ?: "${System.getProperty("user.home")}/app-links-demo.log")

private fun log(message: String) {
    println(message)
    runCatching { logFile.appendText("${ProcessHandle.current().pid()} $message\n") }
}

private val received = mutableStateListOf<URI>()

fun main(args: Array<String>) {
    log("launch args=${args.toList()}")
    nucleusApplication(args) {
        onDeepLink { uri ->
            log("deeplink $uri")
            received += uri
        }
        NucleusDecoratedWindowTheme(isDark = false) {
            DecoratedWindow(onCloseRequest = ::exitApplication, title = "App Links Demo") {
                TitleBar { BasicText("App Links Demo") }
                val links = remember { received }
                Column {
                    BasicText("Deep links received: ${links.size}")
                    links.forEach { BasicText(it.toString()) }
                }
            }
        }
    }
}
