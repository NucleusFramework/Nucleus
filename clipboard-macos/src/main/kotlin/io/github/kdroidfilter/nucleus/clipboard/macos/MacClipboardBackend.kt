package io.github.kdroidfilter.nucleus.clipboard.macos

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
 * macOS [ClipboardBackend] backed by `NSPasteboard.generalPasteboard` via JNI.
 *
 * Registered through `META-INF/services`, so adding `nucleus.clipboard-macos`
 * to the runtime classpath automatically wires up the façade.
 *
 * All IO runs on [Dispatchers.IO]; NSPasteboard is thread-safe so no main-thread
 * hop is required for V1 (delayed rendering, which needs the main thread, is
 * deferred to V2).
 */
class MacClipboardBackend : ClipboardBackend {
    override val name: String = "macOS NSPasteboard"

    override fun isAvailable(): Boolean = Platform.Current == Platform.MacOS && NativeMacClipboardBridge.isLoaded

    override suspend fun readText(): String? = withContext(Dispatchers.IO) { NativeMacClipboardBridge.nativeReadText() }

    override suspend fun readHtml(): String? =
        withContext(Dispatchers.IO) { NativeMacClipboardBridge.nativeReadHtml()?.stripBom() }

    override suspend fun readRtf(): String? = withContext(Dispatchers.IO) { NativeMacClipboardBridge.nativeReadRtf() }

    override suspend fun readImagePng(): ByteArray? =
        withContext(Dispatchers.IO) { NativeMacClipboardBridge.nativeReadImagePng() }

    override suspend fun readFiles(): List<Path> =
        withContext(Dispatchers.IO) {
            NativeMacClipboardBridge.nativeReadFilePaths().map(Paths::get)
        }

    override suspend fun availableFormats(): Set<ClipboardFormat> =
        withContext(Dispatchers.IO) {
            val types = NativeMacClipboardBridge.nativeAvailableTypes()
            buildSet {
                for (uti in types) {
                    when (uti) {
                        "public.utf8-plain-text", "public.plain-text", "public.text", "NSStringPboardType" ->
                            add(ClipboardFormat.Text)
                        "public.html" -> add(ClipboardFormat.Html)
                        "public.rtf" -> add(ClipboardFormat.Rtf)
                        "public.png", "public.tiff", "public.jpeg" -> add(ClipboardFormat.Image)
                        "public.file-url", "NSFilenamesPboardType" -> add(ClipboardFormat.Files)
                    }
                }
            }
        }

    override suspend fun write(payload: ClipboardWritePayload): Boolean =
        withContext(Dispatchers.IO) {
            val paths = payload.files?.map { it.toAbsolutePath().toString() }?.toTypedArray()
            NativeMacClipboardBridge.nativeWrite(
                payload.text,
                payload.html,
                payload.rtf,
                payload.imagePng,
                paths,
            )
        }

    override suspend fun clear(): Boolean = withContext(Dispatchers.IO) { NativeMacClipboardBridge.nativeClear() }

    override fun changeCount(): Long =
        if (NativeMacClipboardBridge.isLoaded) NativeMacClipboardBridge.nativeChangeCount() else 0L

    override fun setAccessBehavior(behavior: AccessBehavior) {
        if (!NativeMacClipboardBridge.isLoaded) return
        NativeMacClipboardBridge.nativeSetAccessBehavior(behavior.ordinal)
    }

    /**
     * Strips a leading UTF-8 BOM (`\uFEFF`) that Firefox and some web apps emit on
     * `public.html` payloads. Keeps the rest untouched.
     */
    private fun String.stripBom(): String = if (isNotEmpty() && this[0] == '\uFEFF') substring(1) else this
}
