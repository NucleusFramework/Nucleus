package io.github.kdroidfilter.nucleus.core.runtime

import io.github.kdroidfilter.nucleus.core.runtime.tools.debugln
import io.github.kdroidfilter.nucleus.core.runtime.tools.errorln
import java.awt.Desktop
import java.io.IOException
import java.net.URI
import java.net.URISyntaxException
import java.nio.file.Files
import java.nio.file.Path

/**
 * Utility object for handling deep links across platforms.
 *
 * On macOS, deep links are delivered via Apple Events (`Desktop.setOpenURIHandler`).
 * On Windows/Linux, deep links are passed as command-line arguments.
 *
 * Integrates with [SingleInstanceManager] to forward deep links from secondary instances
 * to the primary instance via the restore request file mechanism.
 */
object DeepLinkHandler {
    private const val TAG = "DeepLinkHandler"

    /** The last received deep link URI. */
    @Volatile
    var uri: URI? = null
        private set

    private var onDeepLink: ((URI) -> Unit)? = null

    /**
     * Registers deep link handling for the application.
     *
     * **AWT-bound.** This call touches `java.awt.Desktop` to install the macOS
     * Apple Events handler, which forces AWT initialisation. On the Tao backend
     * (`decorated-window-tao`) AWT and Tao both create their own `NSApp`,
     * deadlocking the event loop. Apps using `nucleusApplication { … }` should
     * call its `onDeepLink { … }` API instead — it picks the right path for
     * the active backend.
     *
     * @param args command-line arguments passed to `main()`
     * @param onDeepLink callback invoked each time a deep link URI is received
     */
    @Deprecated(
        "Use nucleusApplication { onDeepLink { … } } — safe across AWT and Tao backends. " +
            "This entry point installs an AWT-only handler and is incompatible with the Tao backend on macOS.",
    )
    fun register(
        args: Array<String>,
        onDeepLink: (URI) -> Unit,
    ) {
        this.onDeepLink = onDeepLink
        installAwtAppleEventHandler()
        parseUriFromArgs(args)
    }

    /**
     * Sets the callback invoked whenever a deep link URI is received and
     * parses any URI present in [args]. Does **not** touch `java.awt.Desktop`,
     * so it is safe on every backend.
     *
     * Used internally by `nucleusApplication`'s `onDeepLink { … }` builder.
     */
    fun setHandler(
        args: Array<String>,
        onDeepLink: (URI) -> Unit,
    ) {
        this.onDeepLink = onDeepLink
        parseUriFromArgs(args)
    }

    /**
     * Installs the AWT-based macOS Apple Events handler. **Calling this
     * initialises AWT** — only invoke from an AWT-driven application launch.
     */
    fun installAwtAppleEventHandler() {
        if (!Desktop.isDesktopSupported()) return
        try {
            Desktop.getDesktop().setOpenURIHandler { event ->
                debugLog { "Received URI via Apple Events: ${event.uri}" }
                handleUri(event.uri)
            }
        } catch (e: UnsupportedOperationException) {
            debugLog { "setOpenURIHandler not supported on this platform: ${e.message}" }
        }
    }

    private fun parseUriFromArgs(args: Array<String>) {
        val raw = args.firstOrNull { it.contains("://") } ?: return
        try {
            val parsed = URI(raw)
            debugLog { "Received URI via CLI args: $parsed" }
            handleUri(parsed)
        } catch (e: URISyntaxException) {
            errorLog { "Failed to parse URI from args: $raw — $e" }
        }
    }

    /**
     * Writes the current [uri] to the given file path.
     * Intended to be called from [SingleInstanceManager]'s `onRestoreFileCreated` callback.
     */
    fun writeUriTo(path: Path) {
        val currentUri = uri ?: return
        try {
            Files.writeString(path, currentUri.toString())
            debugLog { "Wrote URI to $path: $currentUri" }
        } catch (e: IOException) {
            errorLog { "Failed to write URI to $path: $e" }
        }
    }

    /**
     * Reads a URI from the given file path and triggers the [onDeepLink] callback.
     * Intended to be called from [SingleInstanceManager]'s `onRestoreRequest` callback.
     */
    fun readUriFrom(path: Path) {
        try {
            val content = Files.readString(path).trim()
            if (content.isNotEmpty()) {
                val parsed = URI(content)
                debugLog { "Read URI from $path: $parsed" }
                handleUri(parsed)
            }
        } catch (e: IOException) {
            errorLog { "Failed to read URI from $path: $e" }
        } catch (e: URISyntaxException) {
            errorLog { "Failed to read URI from $path: $e" }
        }
    }

    /**
     * Updates [uri] and invokes the registered callback. Used by alternative
     * delivery paths (e.g. the Tao backend's native Apple Events handler) so
     * the [SingleInstanceManager] persistence still sees the latest URI.
     */
    fun deliver(newUri: URI) {
        handleUri(newUri)
    }

    private fun handleUri(newUri: URI) {
        uri = newUri
        onDeepLink?.invoke(newUri)
    }

    private fun debugLog(msg: () -> String) {
        debugln { "[$TAG] ${msg()}" }
    }

    private fun errorLog(msg: () -> String) {
        errorln { "[$TAG] ${msg()}" }
    }
}
