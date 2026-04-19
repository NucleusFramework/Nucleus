package io.github.kdroidfilter.nucleus.clipboard.windows

import io.github.kdroidfilter.nucleus.core.runtime.NativeLibraryLoader

private const val LIBRARY_NAME = "nucleus_clipboard_windows"

/**
 * Low-level JNI surface over the Win32 clipboard (User32).
 *
 * All methods apply to the system clipboard. Each native call opens the
 * clipboard with a bounded retry loop (5 × ~10 ms) to ride out transient
 * contention from `rdpclip.exe` and clipboard-history viewers. Safe to call
 * from any thread; no dedicated message pump is required because
 * [nativeChangeCount] uses `GetClipboardSequenceNumber` (does not require
 * opening the clipboard or a message-only window).
 */
@Suppress("TooManyFunctions")
internal object NativeWindowsClipboardBridge {
    private val loaded = NativeLibraryLoader.load(LIBRARY_NAME, NativeWindowsClipboardBridge::class.java)

    val isLoaded: Boolean get() = loaded

    /** `GetClipboardSequenceNumber()` — cheap, no clipboard open. */
    @JvmStatic
    external fun nativeChangeCount(): Long

    /**
     * Returns the format names currently advertised on the clipboard.
     * Predefined formats are mapped back to canonical strings
     * (`CF_UNICODETEXT`, `CF_HDROP`, `CF_DIBV5`, `CF_DIB`, `CF_BITMAP`);
     * registered formats return their original registered name
     * (`HTML Format`, `Rich Text Format`, `PNG`, MIME strings, …).
     */
    @JvmStatic
    external fun nativeAvailableFormats(): Array<String>

    @JvmStatic
    external fun nativeReadText(): String?

    /** Unwraps the CF_HTML header and returns the fragment bytes decoded as UTF-8. */
    @JvmStatic
    external fun nativeReadHtml(): String?

    @JvmStatic
    external fun nativeReadRtf(): String?

    /**
     * Returns PNG-encoded bytes. Prefers the registered `"PNG"` format when
     * available; otherwise falls back to `CF_DIBV5` / `CF_DIB` transcoded via
     * GDI+.
     */
    @JvmStatic
    external fun nativeReadImagePng(): ByteArray?

    /** Returns absolute paths read from `CF_HDROP`. */
    @JvmStatic
    external fun nativeReadFilePaths(): Array<String>

    /**
     * Atomic multi-format write — opens the clipboard once, empties it, and
     * publishes every non-null representation between one
     * `OpenClipboard`/`CloseClipboard` pair. Images are published both as
     * `"PNG"` (registered) and `CF_DIBV5` for broad compatibility.
     * `paths`, when non-null, produces a `CF_HDROP` plus
     * `"Preferred DropEffect"` = `DROPEFFECT_COPY`.
     */
    @JvmStatic
    external fun nativeWrite(
        text: String?,
        html: String?,
        rtf: String?,
        imagePng: ByteArray?,
        paths: Array<String>?,
    ): Boolean

    @JvmStatic
    external fun nativeClear(): Boolean
}
