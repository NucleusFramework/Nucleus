package com.example.share

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.rememberWindowState
import dev.nucleusframework.application.nucleusApplication
import dev.nucleusframework.application.share
import dev.nucleusframework.application.shareAnchor
import dev.nucleusframework.share.ShareAnchor
import dev.nucleusframework.share.ShareException
import dev.nucleusframework.share.ShareRequest
import dev.nucleusframework.share.ShareSheet
import dev.nucleusframework.share.shareRequest
import dev.nucleusframework.window.TitleBar
import dev.nucleusframework.window.material.MaterialDecoratedWindow
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

/** Shares text, a link, files and a mixed payload through the system share UI. */
fun main(args: Array<String>) =
    nucleusApplication(args) {
        MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
            MaterialDecoratedWindow(
                onCloseRequest = ::exitApplication,
                state = rememberWindowState(size = DpSize(420.dp, 460.dp)),
                title = "Share demo",
            ) {
                TitleBar { Text("Share demo", fontSize = 13.sp) }
                val window = nucleusWindow
                Surface(Modifier.fillMaxSize()) {
                    ShareButtons { request, anchor -> window.share(request, anchor) }
                }
            }
        }
    }

private val samples: List<Pair<String, () -> ShareRequest>> =
    listOf(
        "Text" to {
            shareRequest {
                title = "Share text"
                text("Shared from a Nucleus app")
            }
        },
        "Link" to {
            shareRequest {
                title = "Share link"
                subject = "Nucleus"
                url("https://github.com/NucleusFramework/Nucleus")
            }
        },
        "File" to {
            shareRequest {
                title = "Share file"
                file(sampleFile("nucleus-share.txt", "Hello from Nucleus").path)
            }
        },
        "Mixed" to {
            shareRequest {
                title = "Share mixed payload"
                subject = "Nucleus"
                text("Two files and a link")
                url("https://github.com/NucleusFramework/Nucleus")
                file(sampleFile("first.txt", "First").path)
                file(sampleFile("second.txt", "Second").path)
            }
        },
        "Invalid" to { shareRequest { text(" ") } },
    )

@Composable
private fun ShareButtons(share: suspend (ShareRequest, ShareAnchor?) -> Unit) {
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf(if (ShareSheet.isSupported) "Ready" else "Native bridge unavailable") }
    val density = LocalDensity.current

    suspend fun run(
        label: String,
        request: ShareRequest,
        anchor: ShareAnchor?,
    ) {
        status =
            try {
                share(request, anchor)
                "$label: presented"
            } catch (e: ShareException) {
                "$label: ${e.error} — ${e.message}"
            }
        println("share-demo: $status")
    }

    // `-Dshare.demo.auto=<label>` presses a button once the window is up (smoke tests).
    LaunchedEffect(Unit) {
        val auto = System.getProperty("share.demo.auto") ?: return@LaunchedEffect
        val (label, request) = samples.firstOrNull { it.first.equals(auto, ignoreCase = true) } ?: return@LaunchedEffect
        delay(1_500)
        run(label, request(), null)
    }

    Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        for ((label, request) in samples) {
            var anchor by remember { mutableStateOf<ShareAnchor?>(null) }
            Button(
                onClick = { scope.launch { run(label, request(), anchor) } },
                modifier = Modifier.onGloballyPositioned { anchor = it.shareAnchor(density) },
            ) { Text(label) }
        }
        Text(status)
    }
}

private fun sampleFile(
    name: String,
    content: String,
): File =
    File(System.getProperty("java.io.tmpdir"), "nucleus-share-demo")
        .apply { mkdirs() }
        .resolve(name)
        .apply { writeText(content) }
