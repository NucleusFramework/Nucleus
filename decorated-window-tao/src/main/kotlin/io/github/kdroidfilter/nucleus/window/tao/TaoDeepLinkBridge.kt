package io.github.kdroidfilter.nucleus.window.tao

import java.net.URI
import java.net.URISyntaxException

/**
 * Receives macOS Apple Events URLs from the native Tao runtime and routes them
 * to a Kotlin sink registered by `nucleus-application`'s `TaoLauncher`.
 *
 * The native handler is installed once, very early (before
 * [NativeTaoBridge.nativeRunBlocking]). The user's `onDeepLink { … }` block
 * is registered later, from inside the application scope. Any URI received
 * before the sink is wired is buffered (one slot) and replayed.
 */
object TaoDeepLinkBridge {
    @Volatile
    private var sink: ((URI) -> Unit)? = null

    @Volatile
    private var pending: URI? = null

    /**
     * Installs the macOS `NSAppleEventManager` handler. Idempotent. Safe no-op
     * on Windows / Linux (the JNI symbol resolves to a no-op stub there).
     */
    fun installNativeHandler() {
        if (!NativeTaoBridge.isLoaded) return
        try {
            NativeTaoBridge.nativeAppleEventsInstall()
        } catch (_: UnsatisfiedLinkError) {
            // Non-macOS targets do not export the symbol.
        }
    }

    /**
     * Registers [onUri] as the deep-link sink and replays any URI that
     * arrived before the sink was wired.
     */
    fun setSink(onUri: (URI) -> Unit) {
        sink = onUri
        pending?.let { uri ->
            pending = null
            onUri(uri)
        }
    }

    internal fun onUrlFromNative(raw: String) {
        val uri =
            try {
                URI(raw)
            } catch (_: URISyntaxException) {
                return
            }
        val current = sink
        if (current != null) {
            current(uri)
        } else {
            pending = uri
        }
    }
}
