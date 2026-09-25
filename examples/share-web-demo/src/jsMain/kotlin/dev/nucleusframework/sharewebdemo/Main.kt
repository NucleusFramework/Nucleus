package dev.nucleusframework.sharewebdemo

import dev.nucleusframework.share.ShareException
import dev.nucleusframework.share.ShareRequest
import dev.nucleusframework.share.ShareSheet
import dev.nucleusframework.share.shareRequest
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch

private val scope = MainScope()

/** The `share-demo` payloads on the browser; the Web Share API needs HTTPS or localhost. */
fun main() {
    log("supported: ${ShareSheet.isSupported}")
    button("Text") {
        shareRequest {
            title = "Share text"
            text("Shared from a Nucleus web app")
        }
    }
    button("Link") {
        shareRequest {
            subject = "Nucleus"
            text("Kotlin Multiplatform share sheet")
            url("https://github.com/NucleusFramework/Nucleus")
        }
    }
    button("Generated file") {
        shareRequest {
            subject = "Nucleus"
            fileUri(textBlobUrl("Hello from Nucleus"), mimeType = "text/plain")
        }
    }
    button("Local path (unsupported)") { shareRequest { file("/tmp/report.pdf") } }
}

// Share from the click itself: browsers only honour navigator.share during a user gesture.
private fun button(
    label: String,
    request: () -> ShareRequest,
) = addButton(label) {
    scope.launch {
        try {
            ShareSheet.share(request())
            log("$label: done")
        } catch (e: ShareException) {
            log("$label: ${e.error} — ${e.message}")
        }
    }
}

private fun log(line: String) {
    println(line)
    appendLine(line)
}

private fun addButton(
    label: String,
    onClick: () -> Unit,
): Unit =
    js(
        "{ var b = document.createElement('button'); b.textContent = label; b.onclick = onClick; " +
            "document.getElementById('buttons').appendChild(b); }",
    )

private fun textBlobUrl(text: String): String = js("URL.createObjectURL(new Blob([text], { type: 'text/plain' }))")

private fun appendLine(line: String): Unit =
    js("{ var p = document.createElement('pre'); p.textContent = line; document.body.appendChild(p); }")
