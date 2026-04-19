package io.github.kdroidfilter.nucleus.clipboard.macos

import io.github.kdroidfilter.nucleus.core.runtime.NativeLibraryLoader

private const val LIBRARY_NAME = "nucleus_clipboard"

/**
 * Low-level JNI surface over `NSPasteboard`. All methods operate on the general
 * pasteboard and are safe to call from any thread (NSPasteboard is thread-safe,
 * and the ObjC side wraps each entry in an autorelease pool).
 */
internal object NativeMacClipboardBridge {
    private val loaded = NativeLibraryLoader.load(LIBRARY_NAME, NativeMacClipboardBridge::class.java)

    val isLoaded: Boolean get() = loaded

    /** Returns `NSPasteboard.generalPasteboard.changeCount`. Cheap Mach IPC. */
    @JvmStatic
    external fun nativeChangeCount(): Long

    /** Returns the UTI list advertised by the general pasteboard without reading bytes. */
    @JvmStatic
    external fun nativeAvailableTypes(): Array<String>

    @JvmStatic
    external fun nativeReadText(): String?

    @JvmStatic
    external fun nativeReadHtml(): String?

    @JvmStatic
    external fun nativeReadRtf(): String?

    /** Returns PNG-encoded bytes. If the clipboard carries only TIFF, it is transcoded natively. */
    @JvmStatic
    external fun nativeReadImagePng(): ByteArray?

    /** Returns an array of absolute POSIX paths read from `public.file-url` items. */
    @JvmStatic
    external fun nativeReadFilePaths(): Array<String>

    /**
     * Atomic write — clears the pasteboard then publishes a single `NSPasteboardItem`
     * carrying every non-null representation. Pass `null` for formats you do not
     * want to publish. `paths` must be absolute POSIX paths.
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

    /**
     * Sets `NSPasteboard.accessBehavior`. Values match [AccessBehavior] ordinal:
     * 0 = always allow, 1 = ask every time, 2 = always deny. No-op on macOS < 15.4.
     */
    @JvmStatic
    external fun nativeSetAccessBehavior(value: Int)
}
