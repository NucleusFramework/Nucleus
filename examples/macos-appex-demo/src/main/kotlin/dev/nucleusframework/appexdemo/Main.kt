package dev.nucleusframework.appexdemo

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import java.io.File

/**
 * Demonstrates a Nucleus JVM app shipping a macOS Network Extension `.appex`
 * embedded under `Contents/PlugIns/`.
 *
 * When launched from the packaged `.app`, this window locates its own bundle and
 * lists the embedded extensions, proving that the `.appex` was bundled and that
 * it carries its OWN code signature / entitlements (distinct from the app).
 *
 * Note: this only *inspects* the bundled extension. Actually installing/enabling
 * a Network Extension requires the NetworkExtension management APIs
 * (NEFilterManager / NETunnelProviderManager), reached from the JVM via a native
 * bridge (Kotlin/Native + FFM or JNI) — out of scope for this packaging example.
 * See https://nucleusframework.dev/en/docs/performance/native-code/
 */
fun main() =
    application {
        Window(onCloseRequest = ::exitApplication, title = "Network Extension Demo") {
            MaterialTheme {
                var report by remember { mutableStateOf(inspectBundledExtensions()) }
                Column(
                    modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text("Bundled Network Extensions", style = MaterialTheme.typography.titleLarge)
                    Button(onClick = { report = inspectBundledExtensions() }) { Text("Refresh") }
                    Text(report, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }

/** Walks up from the running executable to the `.app`, then lists the `.appex` bundles in `Contents/PlugIns`. */
private fun inspectBundledExtensions(): String {
    val pluginsDir = locatePlugInsDir()
        ?: return "Not running from a packaged .app bundle.\n" +
            "Package first, then launch the app from the built .app:\n" +
            "  ./gradlew :examples:macos-appex-demo:embedAppex\n" +
            "  open build/compose/binaries/main/app/NetworkExtensionDemo.app"

    val appexes = pluginsDir.listFiles { f -> f.isDirectory && f.name.endsWith(".appex") }?.toList().orEmpty()
    if (appexes.isEmpty()) return "No .appex found under ${pluginsDir.absolutePath}"

    return buildString {
        appendLine("PlugIns: ${pluginsDir.absolutePath}\n")
        for (appex in appexes) {
            appendLine("• ${appex.name}")
            appendLine(codesignInfo(appex).prependIndent("    "))
            appendLine()
        }
    }
}

private fun locatePlugInsDir(): File? {
    // Inside a packaged app the launcher lives at <App>.app/Contents/MacOS/<name>.
    val cmd = ProcessHandle.current().info().command().orElse(null) ?: return null
    val macOsDir = File(cmd).parentFile ?: return null // .../Contents/MacOS
    val contents = macOsDir.parentFile ?: return null // .../Contents
    if (contents.name != "Contents") return null
    return File(contents, "PlugIns").takeIf { it.isDirectory }
}

/** Reads the extension's real signature + entitlements via the codesign CLI. */
private fun codesignInfo(appex: File): String =
    try {
        val proc = ProcessBuilder(
            "/usr/bin/codesign", "-d", "--verbose=2", "--entitlements", ":-", appex.absolutePath,
        ).redirectErrorStream(true).start()
        val out = proc.inputStream.bufferedReader().readText()
        proc.waitFor()
        out.trim().ifEmpty { "(no signature information)" }
    } catch (e: Exception) {
        "codesign inspection failed: ${e.message}"
    }
