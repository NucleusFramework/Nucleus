package io.github.kdroidfilter.nucleus.clipboard

import io.github.kdroidfilter.nucleus.clipboard.internal.BackendFactory
import io.github.kdroidfilter.nucleus.clipboard.internal.ClipboardBackend
import io.github.kdroidfilter.nucleus.clipboard.internal.ClipboardWritePayload
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.nio.file.Path
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

private val DEFAULT_POLL_INTERVAL: Duration = 250.milliseconds

/**
 * Rich cross-platform clipboard façade.
 *
 * Reads and writes text, HTML, RTF, images, and file lists. Emits change events
 * via [watch] as a cold [Flow]. Delegates to the first available platform backend
 * discovered through [java.util.ServiceLoader] — on macOS that's
 * `nucleus.clipboard-macos`; when no backend is loaded, all methods degrade to
 * no-ops returning `null` / `false`.
 *
 * All suspending methods hop to the backend's native thread internally; callers
 * may invoke them from any coroutine context.
 */
@Suppress("TooManyFunctions")
object Clipboard {
    private val backend: ClipboardBackend by lazy { BackendFactory.discover() }

    /** True when a platform backend is loaded and operational. */
    val isAvailable: Boolean get() = backend.isAvailable()

    /** Backend name for diagnostics (e.g. `"macOS NSPasteboard"` or `"no-op"`). */
    val backendName: String get() = backend.name

    // --- Read ---

    suspend fun readText(): String? = backend.readText()

    suspend fun readHtml(): String? = backend.readHtml()

    suspend fun readRtf(): String? = backend.readRtf()

    /** Returns PNG-encoded bytes. Use your preferred decoder (`ImageIO.read` for `BufferedImage`). */
    suspend fun readImageBytes(): ByteArray? = backend.readImagePng()

    suspend fun readFiles(): List<Path> = backend.readFiles()

    /**
     * Returns the set of formats currently advertised by the clipboard without
     * reading any content bytes. Safe to call under restrictive pasteboard-privacy
     * policies (macOS 15.4+).
     */
    suspend fun availableFormats(): Set<ClipboardFormat> = backend.availableFormats()

    // --- Write ---

    suspend fun writeText(text: String): Boolean =
        backend.write(ClipboardWritePayload(text = text, html = null, rtf = null, imagePng = null, files = null))

    suspend fun writeHtml(
        html: String,
        plainTextFallback: String? = null,
    ): Boolean =
        backend.write(
            ClipboardWritePayload(
                text = plainTextFallback,
                html = html,
                rtf = null,
                imagePng = null,
                files = null,
            ),
        )

    suspend fun writeRtf(
        rtf: String,
        plainTextFallback: String? = null,
    ): Boolean =
        backend.write(
            ClipboardWritePayload(
                text = plainTextFallback,
                html = null,
                rtf = rtf,
                imagePng = null,
                files = null,
            ),
        )

    suspend fun writeImage(png: ByteArray): Boolean =
        backend.write(ClipboardWritePayload(text = null, html = null, rtf = null, imagePng = png, files = null))

    suspend fun writeFiles(paths: List<Path>): Boolean =
        backend.write(ClipboardWritePayload(text = null, html = null, rtf = null, imagePng = null, files = paths))

    /**
     * Atomic multi-format write. Richer representations (HTML, RTF, image) coexist
     * with a plain-text fallback on the same clipboard item.
     *
     * ```
     * Clipboard.write {
     *     html = "<b>Hello</b>"
     *     text = "Hello"
     * }
     * ```
     */
    suspend fun write(block: ClipboardWriteScope.() -> Unit): Boolean {
        val scope = ClipboardWriteScope().apply(block)
        val payload = scope.toPayload()
        if (payload.isEmpty) return false
        return backend.write(payload)
    }

    suspend fun clear(): Boolean = backend.clear()

    /** Sets the platform privacy policy for background reads. No-op outside macOS 15.4+. */
    fun setAccessBehavior(behavior: AccessBehavior) = backend.setAccessBehavior(behavior)

    /**
     * Cold [Flow] that emits whenever the clipboard contents change.
     *
     * The event carries only format metadata and a monotonic `changeCount`, not
     * payload bytes — call a `readXxx` method to fetch content. Polls the backend's
     * change counter (cheap Mach IPC on macOS, WM_CLIPBOARDUPDATE on Windows,
     * XFixes on X11, data-control events on Wayland).
     *
     * The baseline is captured on `collect`, so the flow does not emit the current
     * clipboard state — only subsequent changes.
     */
    fun watch(pollInterval: Duration = DEFAULT_POLL_INTERVAL): Flow<ClipboardEvent> =
        callbackFlow {
            var last = backend.changeCount()
            val job =
                launch {
                    while (isActive) {
                        delay(pollInterval)
                        val cur = backend.changeCount()
                        if (cur != last) {
                            last = cur
                            trySend(ClipboardEvent(formats = availableFormats(), changeCount = cur))
                        }
                    }
                }
            awaitClose { job.cancel() }
        }
}
