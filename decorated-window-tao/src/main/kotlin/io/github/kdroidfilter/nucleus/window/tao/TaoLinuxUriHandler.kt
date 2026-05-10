package io.github.kdroidfilter.nucleus.window.tao

import androidx.compose.ui.platform.UriHandler

/**
 * Linux-only [UriHandler] used by [DecoratedWindow] on the Tao backend.
 *
 * Compose Desktop's default `LocalUriHandler` goes through Skiko's
 * `URIManager.openUri` → `java.awt.Desktop.getDesktop().browse(URI)`. On Linux
 * that initialises XAWT — its own X11 connection plus event-pump thread — and
 * deadlocks against our GLX/Tao event loop, freezing the whole app on every
 * external-link click. Spawning `xdg-open` async via [ProcessBuilder] avoids
 * the AWT toolkit entirely.
 */
internal object TaoLinuxUriHandler : UriHandler {
    override fun openUri(uri: String) {
        Thread(
            {
                runCatching {
                    ProcessBuilder("xdg-open", uri)
                        .redirectError(ProcessBuilder.Redirect.DISCARD)
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                        .start()
                }
            },
            "TaoLinuxUriHandler",
        ).apply { isDaemon = true }.start()
    }
}
