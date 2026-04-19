package io.github.kdroidfilter.nucleus.clipboard.linux

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
 * Linux [ClipboardBackend] with a two-backend strategy:
 *
 * - **X11** — native XCB + XFixes via JNI (`libnucleus_clipboard_linux`).
 *   Supports the full format matrix (text, HTML, RTF, image/png, files) and
 *   delivers change events through XFixes selection notifications.
 * - **Wayland** — delegates to `wl-copy` / `wl-paste` from the `wl-clipboard`
 *   project. This already handles the three protocol variants
 *   (`ext-data-control-v1`, `zwlr_data_control_v1`, and focus-stealing core)
 *   plus GNOME quirks that a native implementation would have to replicate.
 *
 * Selection heuristic:
 * - Wayland (`WAYLAND_DISPLAY` or `XDG_SESSION_TYPE=wayland`) → Wayland delegate
 *   if `wl-copy`/`wl-paste` are on `PATH`, otherwise fall back to the X11
 *   native path (Xwayland bridges CLIPBOARD for X11 clients).
 * - X11 → native XCB backend.
 */
@Suppress("TooManyFunctions")
class LinuxClipboardBackend : ClipboardBackend {
    private enum class Mode { X11, WAYLAND, UNAVAILABLE }

    private val wayland = WaylandClipboardDelegate()

    private val mode: Mode by lazy { resolveMode() }

    override val name: String
        get() =
            when (mode) {
                Mode.X11 -> "Linux X11 (XCB+XFixes)"
                Mode.WAYLAND -> "Linux Wayland (wl-clipboard)"
                Mode.UNAVAILABLE -> "Linux unavailable"
            }

    override fun isAvailable(): Boolean = mode != Mode.UNAVAILABLE

    private fun resolveMode(): Mode {
        if (Platform.Current != Platform.Linux) return Mode.UNAVAILABLE
        if (Platform.isWayland && wayland.isAvailable) {
            wayland.startWatcher()
            return Mode.WAYLAND
        }
        if (NativeX11ClipboardBridge.isLoaded && NativeX11ClipboardBridge.nativeInit()) {
            return Mode.X11
        }
        // Wayland session without wl-clipboard but with Xwayland available
        // (rare on modern distros — wl-clipboard is almost always shipped).
        return Mode.UNAVAILABLE
    }

    // --- Reads ---

    override suspend fun readText(): String? =
        withContext(Dispatchers.IO) {
            when (mode) {
                Mode.X11 -> NativeX11ClipboardBridge.nativeReadText()
                Mode.WAYLAND -> wayland.readText()
                Mode.UNAVAILABLE -> null
            }
        }

    override suspend fun readHtml(): String? =
        withContext(Dispatchers.IO) {
            when (mode) {
                Mode.X11 -> NativeX11ClipboardBridge.nativeReadHtml()
                Mode.WAYLAND -> wayland.readHtml()
                Mode.UNAVAILABLE -> null
            }?.stripBom()
        }

    override suspend fun readRtf(): String? =
        withContext(Dispatchers.IO) {
            when (mode) {
                Mode.X11 -> NativeX11ClipboardBridge.nativeReadRtf()
                Mode.WAYLAND -> wayland.readRtf()
                Mode.UNAVAILABLE -> null
            }
        }

    override suspend fun readImagePng(): ByteArray? =
        withContext(Dispatchers.IO) {
            when (mode) {
                Mode.X11 -> NativeX11ClipboardBridge.nativeReadImagePng()
                Mode.WAYLAND -> wayland.readImagePng()
                Mode.UNAVAILABLE -> null
            }
        }

    override suspend fun readFiles(): List<Path> =
        withContext(Dispatchers.IO) {
            when (mode) {
                Mode.X11 -> {
                    val entries = NativeX11ClipboardBridge.nativeReadFilePaths()
                    // First entry carries the copy/cut marker ("c" or "m"); we
                    // currently don't expose cut-vs-copy in the public API so
                    // it is discarded.
                    entries
                        .drop(1)
                        .map(Paths::get)
                }
                Mode.WAYLAND -> wayland.readFiles()
                Mode.UNAVAILABLE -> emptyList()
            }
        }

    override suspend fun availableFormats(): Set<ClipboardFormat> =
        withContext(Dispatchers.IO) {
            val targets: List<String> =
                when (mode) {
                    Mode.X11 -> NativeX11ClipboardBridge.nativeAvailableTargets().toList()
                    Mode.WAYLAND -> wayland.listTypes()
                    Mode.UNAVAILABLE -> emptyList()
                }
            targets.toClipboardFormats()
        }

    // --- Writes ---

    override suspend fun write(payload: ClipboardWritePayload): Boolean =
        withContext(Dispatchers.IO) {
            when (mode) {
                Mode.X11 -> {
                    val paths = payload.files?.map { it.toAbsolutePath().toString() }?.toTypedArray()
                    NativeX11ClipboardBridge.nativeWrite(
                        payload.text,
                        payload.html,
                        payload.rtf,
                        payload.imagePng,
                        paths,
                        false,
                    )
                }
                Mode.WAYLAND ->
                    wayland.write(
                        text = payload.text,
                        html = payload.html,
                        rtf = payload.rtf,
                        imagePng = payload.imagePng,
                        files = payload.files,
                    )
                Mode.UNAVAILABLE -> false
            }
        }

    override suspend fun clear(): Boolean =
        withContext(Dispatchers.IO) {
            when (mode) {
                Mode.X11 -> NativeX11ClipboardBridge.nativeClear()
                Mode.WAYLAND -> wayland.clear()
                Mode.UNAVAILABLE -> false
            }
        }

    override fun changeCount(): Long =
        when (mode) {
            Mode.X11 -> NativeX11ClipboardBridge.nativeChangeCount()
            Mode.WAYLAND -> wayland.changeCount()
            Mode.UNAVAILABLE -> 0L
        }

    // Linux has no OS-level pasteboard privacy toggle — callers are unrestricted.
    override fun setAccessBehavior(behavior: AccessBehavior) = Unit

    override fun accessBehavior(): AccessBehavior? = null

    override fun isAccessBehaviorSupported(): Boolean = false

    private fun String.stripBom(): String =
        when {
            isEmpty() -> this
            this[0] == '\uFEFF' -> substring(1)
            else -> this
        }

    private fun List<String>.toClipboardFormats(): Set<ClipboardFormat> =
        buildSet {
            for (t in this@toClipboardFormats) {
                when {
                    t.equals("UTF8_STRING", ignoreCase = true) ||
                        t.equals("STRING", ignoreCase = true) ||
                        t.equals("TEXT", ignoreCase = true) ||
                        t.equals("COMPOUND_TEXT", ignoreCase = true) ||
                        t.startsWith("text/plain", ignoreCase = true) -> add(ClipboardFormat.Text)
                    t.equals("text/html", ignoreCase = true) -> add(ClipboardFormat.Html)
                    t.equals("text/rtf", ignoreCase = true) ||
                        t.equals("application/rtf", ignoreCase = true) -> add(ClipboardFormat.Rtf)
                    t.startsWith("image/", ignoreCase = true) -> add(ClipboardFormat.Image)
                    t.equals("text/uri-list", ignoreCase = true) ||
                        t.equals("x-special/gnome-copied-files", ignoreCase = true) ||
                        t.equals("application/x-kde-cutselection", ignoreCase = true) -> add(ClipboardFormat.Files)
                }
            }
        }
}
