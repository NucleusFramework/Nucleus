package dev.nucleusframework.core.runtime

import dev.nucleusframework.core.runtime.tools.debugln
import dev.nucleusframework.core.runtime.tools.errorln
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
public object DeepLinkHandler {
    private const val TAG = "DeepLinkHandler"

    /** The last received deep link URI. */
    @Volatile
    public var uri: URI? = null
        private set

    private var onDeepLink: ((URI) -> Unit)? = null

    /**
     * Guards [setHandler] against re-delivering the cold-start CLI URI. A handler
     * registered from a Composable scope (`nucleusApplication { onDeepLink { … } }`)
     * re-runs on every recomposition; without this flag, each call would re-parse the
     * launch args and fire the same URI again — opening duplicate tabs/windows.
     */
    @Volatile
    private var coldStartArgsDelivered = false

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
    public fun register(
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
    public fun setHandler(
        args: Array<String>,
        onDeepLink: (URI) -> Unit,
    ) {
        this.onDeepLink = onDeepLink
        // Deliver the cold-start CLI URI to a handler exactly once over the process
        // lifetime. setHandler may be called repeatedly (e.g. from a Composable scope
        // re-running on recomposition); re-parsing the args each time would re-fire the
        // same launch URI. Restore-request relays go through readUriFrom/handleUri and
        // are unaffected — each is a genuine new event.
        if (!coldStartArgsDelivered) {
            coldStartArgsDelivered = true
            parseUriFromArgs(args)
        }
    }

    /**
     * Captures any deep link URI present in [args] into [uri] without
     * installing a callback or touching `java.awt.Desktop`. Safe on every
     * backend.
     *
     * Used by the single-instance bootstrap of a secondary launch (e.g. a
     * Windows jump-list relaunch): the process must record its URI *before*
     * forwarding it via [writeUriTo] and exiting — well before the composition
     * (and its `onDeepLink { … }` registration) would ever run.
     */
    public fun captureFromArgs(args: Array<String>) {
        parseUriFromArgs(args)
    }

    /**
     * Installs the AWT-based macOS Apple Events handler. **Calling this
     * initialises AWT** — only invoke from an AWT-driven application launch.
     */
    public fun installAwtAppleEventHandler() {
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
        // Accept hierarchical (`myapp://host`) and opaque (`myapp:?q=1`) forms.
        // The previous `://` filter dropped valid scheme-only URIs (issue #414).
        val raw = args.firstOrNull(::isDeepLinkArg) ?: return
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
    public fun writeUriTo(path: Path) {
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
    public fun readUriFrom(path: Path) {
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
    public fun deliver(newUri: URI) {
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

/**
 * Whether [arg] looks like a deep-link URI (RFC 3986 scheme).
 *
 * Matches `scheme:` for any valid scheme so opaque URIs such as
 * `myapp:?queryParam=123` are accepted alongside hierarchical
 * `myapp://host/path` forms. Windows drive paths (`C:\…`, `D:/…`)
 * are rejected even though they contain a colon.
 */
internal fun isDeepLinkArg(arg: String): Boolean {
    val colon = arg.indexOf(':')
    if (colon <= 0) return false
    val scheme = arg.substring(0, colon)
    if (!scheme[0].isLetter()) return false
    if (scheme.any { !it.isLetterOrDigit() && it != '+' && it != '-' && it != '.' }) return false
    // Windows absolute paths: single-letter scheme + \ or /
    if (scheme.length == 1 && arg.length > colon + 1) {
        val next = arg[colon + 1]
        if (next == '\\' || next == '/') return false
    }
    return true
}
