// `ClipEntry` / `nativeClipEntry` (the only way to build or read a desktop clip
// entry) are experimental, and `Clipboard.nativeClipboard` is deprecated in
// favour of the platform extension — both are unavoidable when implementing the
// interface itself.
@file:OptIn(ExperimentalComposeUiApi::class)
@file:Suppress("DEPRECATION")

package dev.nucleusframework.window.tao.clipboard

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import dev.nucleusframework.window.tao.ffi.NativeTaoLinuxClipboardBridge
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.datatransfer.Transferable
import java.util.logging.Level
import java.util.logging.Logger
import kotlin.coroutines.resume

/**
 * Compose [Clipboard] backed by GTK instead of AWT, for the Tao backend on
 * Linux (issue #582).
 *
 * Compose Desktop's only implementation reads
 * `java.awt.Toolkit.getSystemClipboard()`, which is X11-only on Linux. A Tao
 * window is a GTK window on whichever GDK backend the session provides, so on
 * a Wayland session the two disagree: KWin publishes the Wayland selection to
 * XWayland only while an X11 window is *active*, so `Ctrl+V` in a
 * Wayland-native window pastes nothing, and with no XWayland at all the AWT
 * call is headless. Going through GTK puts the clipboard on the same backend
 * as the window.
 *
 * Everything is delegated to [fallback] when the native helper is unavailable
 * (missing library, headless process, GTK never initialised), so this is safe
 * to install unconditionally on Linux.
 *
 * Only `text/plain` crosses the process boundary. Compose's own
 * `AnnotatedString` flavor is JVM-local (`DataFlavor(AnnotatedString::class)`)
 * and cannot be published to any real selection — AWT cannot do it either —
 * so [lastWritten] keeps the last entry we published and hands it back when
 * the selection still holds that same text, preserving span styles for
 * copy/paste inside the app.
 *
 * Threading: GTK is only touched from `Dispatchers.Main`, which on this
 * backend *is* the GTK main thread (Tao's native event loop).
 */
internal class TaoLinuxClipboard(
    private val fallback: Clipboard,
) : Clipboard {
    /**
     * Text we published, with the entry it came from. See the class doc.
     * Volatile because a `setClipEntry` may start on any thread while the GTK
     * work itself hops to `Dispatchers.Main`.
     */
    @Volatile
    private var lastWritten: Pair<String, ClipEntry>? = null

    override suspend fun getClipEntry(): ClipEntry? {
        if (!NativeTaoLinuxClipboardBridge.isAvailable) return fallback.getClipEntry()
        val text = withContext(Dispatchers.Main) { requestText() } ?: return null
        lastWritten?.let { (written, entry) -> if (written == text) return entry }
        return ClipEntry(StringSelection(text))
    }

    override suspend fun setClipEntry(clipEntry: ClipEntry?) {
        if (!NativeTaoLinuxClipboardBridge.isAvailable) {
            fallback.setClipEntry(clipEntry)
            return
        }
        if (clipEntry == null) {
            lastWritten = null
            withContext(Dispatchers.Main) { NativeTaoLinuxClipboardBridge.nativeClear() }
            return
        }
        // Reading a foreign transferable can block (it may pull the data from
        // another process), hence IO rather than the GTK thread.
        val text = withContext(Dispatchers.IO) { (clipEntry.nativeClipEntry as? Transferable)?.plainTextOrNull() }
        if (text == null) {
            logger.log(Level.FINE, "Clip entry carries no text/plain flavor; clipboard left unchanged")
            return
        }
        withContext(Dispatchers.Main) {
            if (NativeTaoLinuxClipboardBridge.nativeSetTextUtf8(text.toByteArray())) {
                lastWritten = text to clipEntry
            } else {
                lastWritten = null
                logger.log(Level.FINE, "GTK refused the selection; clipboard left unchanged")
            }
        }
    }

    /**
     * The AWT clipboard when there is one — `getAsAwtClipboard()` type-checks
     * the value, so callers that reach for the platform object keep working
     * (and keep AWT's X11 semantics) instead of seeing a foreign type.
     */
    override val nativeClipboard: Any
        get() =
            runCatching { fallback.nativeClipboard }
                .getOrElse { NoNativeClipboard }

    /**
     * Suspends on GTK's asynchronous text request. The callback may fire
     * *synchronously* when this process owns the selection, which
     * [suspendCancellableCoroutine] handles; a request GTK never accepted
     * resumes with null instead of hanging the caller forever.
     */
    private suspend fun requestText(): String? =
        suspendCancellableCoroutine { continuation ->
            val requested = NativeTaoLinuxClipboardBridge.nativeRequestTextUtf8(ResumeOnText(continuation))
            if (!requested) continuation.resume(null)
        }

    /**
     * Named class rather than a lambda so there is a single, GraalVM-declarable
     * implementation of [NativeTaoLinuxClipboardBridge.TextCallback] — the
     * native side looks `onText` up on the concrete class.
     */
    private class ResumeOnText(
        private val continuation: CancellableContinuation<String?>,
    ) : NativeTaoLinuxClipboardBridge.TextCallback {
        override fun onText(utf8: ByteArray?) {
            // Resuming an already-cancelled continuation is a no-op, so a
            // caller that gave up while GTK was fetching is not an error.
            continuation.resume(utf8?.toString(Charsets.UTF_8))
        }
    }

    private companion object {
        val logger: Logger = Logger.getLogger(TaoLinuxClipboard::class.java.name)

        /** Stand-in for `Clipboard.nativeClipboard` when AWT has none to give. */
        object NoNativeClipboard
    }
}

/**
 * `text/plain` content of the transferable, or null when it carries none.
 * Swallows the `IOException` / `UnsupportedFlavorException` pair AWT throws
 * when the owning process died between the flavor check and the read.
 */
internal fun Transferable.plainTextOrNull(): String? =
    runCatching {
        if (isDataFlavorSupported(DataFlavor.stringFlavor)) {
            getTransferData(DataFlavor.stringFlavor) as? String
        } else {
            null
        }
    }.getOrNull()
