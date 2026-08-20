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
import dev.nucleusframework.window.tao.dispatch.TaoMainDispatcher
import dev.nucleusframework.window.tao.ffi.NativeTaoLinuxClipboardBridge
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
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
 * Text, images and file lists are all carried natively, so this is not a
 * narrower clipboard than the AWT one it replaces — it is the same set of
 * flavors, freed from XWayland. What the selection advertises decides which
 * flavors a clip entry exposes; the image and the file list are only fetched
 * if something reads them. A selection carrying *only* formats GTK is not
 * asked about here (RTF, private targets) is left to [fallback], where X11 may
 * still serve it.
 *
 * Compose's own `AnnotatedString` flavor is JVM-local
 * (`DataFlavor(AnnotatedString::class)`) and cannot be published to any real
 * selection — AWT cannot do it either — so [lastWritten] keeps the last entry
 * we published and hands it back while the selection still holds that text,
 * preserving span styles for copy/paste inside the app.
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
        val targets = withContext(Dispatchers.Main) { requestTargets() }
        if (targets.isEmpty()) return null

        val hasText = targets.any(::isTextTarget)
        val hasImage = targets.any { it.startsWith(IMAGE_PREFIX) }
        val hasFiles = URI_LIST_TARGET in targets
        if (!hasText && !hasImage && !hasFiles) {
            // RTF, vendor targets: nothing here knows how to read them, and the
            // X11 bridge might, so let AWT have its chance rather than
            // reporting an empty clipboard.
            logger.log(Level.FINE, "Selection offers no format GTK is asked about here: $targets")
            return fallback.getClipEntry()
        }

        val text = if (hasText) withContext(Dispatchers.Main) { requestText() } else null
        text?.let { published -> lastWritten?.let { (written, entry) -> if (written == published) return entry } }
        // Listing the targets and fetching the text are two round-trips, so the
        // selection can change hands in between. Report an empty clipboard
        // rather than asking AWT, which would answer about a *different*
        // selection — the very confusion #582 is about.
        if (text == null && !hasImage && !hasFiles) return null

        return ClipEntry(
            GtkClipboardTransferable(
                text = text,
                fetchPng = if (hasImage) ::blockingImagePng else null,
                fetchUriList = if (hasFiles) ::blockingUriList else null,
            ),
        )
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
        val transferable = clipEntry.nativeClipEntry as? Transferable
        // Reading a foreign transferable can block (it may pull the data from
        // another process), hence IO rather than the GTK thread.
        val payload = withContext(Dispatchers.IO) { transferable?.toGtkPayload() }
        if (payload == null) {
            logger.log(Level.FINE, "Clip entry carries no format GTK can publish; handing it to AWT")
            fallback.setClipEntry(clipEntry)
            return
        }
        val published = withContext(Dispatchers.Main) { payload.publish() }
        lastWritten = if (published && payload is GtkPayload.Text) payload.text to clipEntry else null
        if (!published) logger.log(Level.FINE, "GTK refused the selection; clipboard left unchanged")
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
     * What the selection advertises, as MIME / atom names, minus the
     * housekeeping atoms every selection carries — they say nothing about the
     * content and a selection reduced to them is an empty one.
     */
    private suspend fun requestTargets(): Set<String> =
        requestBytes(NativeTaoLinuxClipboardBridge::nativeRequestTargetsUtf8)
            ?.toString(Charsets.UTF_8)
            ?.split('\n')
            ?.filter { it.isNotEmpty() && it !in HOUSEKEEPING_TARGETS }
            ?.toSet()
            .orEmpty()

    private suspend fun requestText(): String? =
        requestBytes(NativeTaoLinuxClipboardBridge::nativeRequestTextUtf8)?.toString(Charsets.UTF_8)

    /**
     * Suspends on one of GTK's asynchronous requests. The callback may fire
     * *synchronously* when this process owns the selection, which
     * [suspendCancellableCoroutine] handles; a request GTK never accepted
     * resumes with null instead of hanging the caller forever.
     */
    private suspend fun requestBytes(request: (NativeTaoLinuxClipboardBridge.BytesCallback) -> Boolean): ByteArray? =
        suspendCancellableCoroutine { continuation ->
            if (!request(ResumeOnBytes(continuation))) continuation.resume(null)
        }

    /**
     * Blocking counterparts for [GtkClipboardTransferable], which is read from
     * whatever thread AWT's `getTransferData` is called on. On the GTK thread
     * the nested-main-loop `wait_*` calls are the only legal option; elsewhere
     * the asynchronous request is driven from Main while the caller blocks.
     */
    private fun blockingImagePng(): ByteArray? =
        onGtkThread(
            sync = NativeTaoLinuxClipboardBridge::nativeWaitForImagePng,
            async = { requestBytes(NativeTaoLinuxClipboardBridge::nativeRequestImagePng) },
        )

    private fun blockingUriList(): String? =
        onGtkThread(
            sync = { NativeTaoLinuxClipboardBridge.nativeWaitForUriListUtf8() },
            async = { requestBytes(NativeTaoLinuxClipboardBridge::nativeRequestUriListUtf8) },
        )?.toString(Charsets.UTF_8)

    private fun <T> onGtkThread(
        sync: () -> T,
        async: suspend () -> T,
    ): T =
        if (Thread.currentThread() === TaoMainDispatcher.taoMainThread) {
            sync()
        } else {
            runBlocking(Dispatchers.Main) { async() }
        }

    /**
     * Named class rather than a lambda so there is a single, GraalVM-declarable
     * implementation of [NativeTaoLinuxClipboardBridge.BytesCallback] — the
     * native side looks `onBytes` up on the concrete class.
     */
    private class ResumeOnBytes(
        private val continuation: CancellableContinuation<ByteArray?>,
    ) : NativeTaoLinuxClipboardBridge.BytesCallback {
        override fun onBytes(bytes: ByteArray?) {
            // Resuming an already-cancelled continuation is a no-op, so a
            // caller that gave up while GTK was fetching is not an error.
            continuation.resume(bytes)
        }
    }

    private companion object {
        val logger: Logger = Logger.getLogger(TaoLinuxClipboard::class.java.name)

        const val IMAGE_PREFIX = "image/"
        const val URI_LIST_TARGET = "text/uri-list"

        /** The X11 text atoms GTK registers alongside the `text/plain` MIME types. */
        val TEXT_ATOMS = setOf("UTF8_STRING", "STRING", "TEXT", "COMPOUND_TEXT")

        /** Selection protocol atoms, present whatever the content is. */
        val HOUSEKEEPING_TARGETS = setOf("TARGETS", "TIMESTAMP", "MULTIPLE", "SAVE_TARGETS", "DELETE")

        fun isTextTarget(target: String): Boolean = target in TEXT_ATOMS || target.startsWith("text/plain")

        /** Stand-in for `Clipboard.nativeClipboard` when AWT has none to give. */
        object NoNativeClipboard
    }
}
