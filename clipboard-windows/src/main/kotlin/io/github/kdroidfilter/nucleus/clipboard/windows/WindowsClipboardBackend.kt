package io.github.kdroidfilter.nucleus.clipboard.windows

import io.github.kdroidfilter.nucleus.clipboard.AccessBehavior
import io.github.kdroidfilter.nucleus.clipboard.ClipboardFormat
import io.github.kdroidfilter.nucleus.clipboard.internal.ClipboardBackend
import io.github.kdroidfilter.nucleus.clipboard.internal.ClipboardWritePayload
import io.github.kdroidfilter.nucleus.core.runtime.Platform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Windows [ClipboardBackend] backed by the Win32 clipboard API via JNI.
 *
 * Registered through `META-INF/services`. Supports text (`CF_UNICODETEXT`),
 * HTML (`"HTML Format"` with CF_HTML header), RTF (`"Rich Text Format"`),
 * images (`"PNG"` + `CF_DIBV5`), and file lists (`CF_HDROP` + preferred
 * drop effect). Change detection uses `GetClipboardSequenceNumber()` — the
 * public [io.github.kdroidfilter.nucleus.clipboard.Clipboard.watch] façade
 * polls it on a 250 ms interval, so no message-only window is needed.
 */
class WindowsClipboardBackend : ClipboardBackend {
    override val name: String = "Windows Win32 Clipboard"

    override fun isAvailable(): Boolean = Platform.Current == Platform.Windows && NativeWindowsClipboardBridge.isLoaded

    override suspend fun readText(): String? =
        withContext(Dispatchers.IO) { NativeWindowsClipboardBridge.nativeReadText() }

    override suspend fun readHtml(): String? =
        withContext(Dispatchers.IO) { NativeWindowsClipboardBridge.nativeReadHtml()?.stripBom() }

    override suspend fun readRtf(): String? =
        withContext(Dispatchers.IO) { NativeWindowsClipboardBridge.nativeReadRtf() }

    override suspend fun readImagePng(): ByteArray? =
        withContext(Dispatchers.IO) { NativeWindowsClipboardBridge.nativeReadImagePng() }

    override suspend fun readFiles(): List<Path> =
        withContext(Dispatchers.IO) {
            NativeWindowsClipboardBridge.nativeReadFilePaths().map(Paths::get)
        }

    override suspend fun availableFormats(): Set<ClipboardFormat> =
        withContext(Dispatchers.IO) {
            val formats = NativeWindowsClipboardBridge.nativeAvailableFormats()
            buildSet {
                for (f in formats) {
                    when {
                        f.equals("CF_UNICODETEXT", ignoreCase = true) ||
                            f.equals("CF_TEXT", ignoreCase = true) ||
                            f.equals("CF_OEMTEXT", ignoreCase = true) -> add(ClipboardFormat.Text)
                        f.equals("HTML Format", ignoreCase = true) ||
                            f.equals("text/html", ignoreCase = true) -> add(ClipboardFormat.Html)
                        f.equals("Rich Text Format", ignoreCase = true) ||
                            f.equals("Rich Text Format Without Objects", ignoreCase = true) ||
                            f.equals("application/rtf", ignoreCase = true) -> add(ClipboardFormat.Rtf)
                        f.equals("PNG", ignoreCase = true) ||
                            f.equals("JFIF", ignoreCase = true) ||
                            f.equals("CF_DIBV5", ignoreCase = true) ||
                            f.equals("CF_DIB", ignoreCase = true) ||
                            f.equals("CF_BITMAP", ignoreCase = true) ||
                            f.startsWith("image/", ignoreCase = true) -> add(ClipboardFormat.Image)
                        f.equals("CF_HDROP", ignoreCase = true) -> add(ClipboardFormat.Files)
                    }
                }
            }
        }

    override suspend fun write(payload: ClipboardWritePayload): Boolean =
        withContext(Dispatchers.IO) {
            val paths = payload.files?.map { it.toAbsolutePath().toString() }?.toTypedArray()
            NativeWindowsClipboardBridge.nativeWrite(
                payload.text,
                payload.html,
                payload.rtf,
                payload.imagePng,
                paths,
            )
        }

    override suspend fun clear(): Boolean = withContext(Dispatchers.IO) { NativeWindowsClipboardBridge.nativeClear() }

    override fun changeCount(): Long =
        if (NativeWindowsClipboardBridge.isLoaded) NativeWindowsClipboardBridge.nativeChangeCount() else 0L

    // Windows has no per-process pasteboard privacy toggle — callers are unrestricted.
    override fun setAccessBehavior(behavior: AccessBehavior) = Unit

    override fun accessBehavior(): AccessBehavior? = null

    override fun isAccessBehaviorSupported(): Boolean = false

    /**
     * Strips a leading UTF-8 BOM (`\uFEFF`) that Chromium emits on `text/html`
     * payloads. CF_HTML's own ASCII header is already consumed by the native
     * side.
     */
    private fun String.stripBom(): String = if (isNotEmpty() && this[0] == '\uFEFF') substring(1) else this
}
