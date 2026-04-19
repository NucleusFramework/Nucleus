package io.github.kdroidfilter.nucleus.clipboard.linux

import io.github.kdroidfilter.nucleus.core.runtime.NativeLibraryLoader

private const val LIBRARY_NAME = "nucleus_clipboard_linux"

/**
 * JNI surface over XCB + XFixes for the X11 CLIPBOARD selection.
 *
 * The native layer opens its own XCB connection, runs a dedicated event thread,
 * and owns a single hidden 1×1 InputOnly window used as both owner and requestor.
 * All public entry points here are thread-safe; reads block the calling thread
 * for up to 2 s while the XCB event thread services the `SelectionNotify` round
 * trip.
 */
@Suppress("TooManyFunctions")
internal object NativeX11ClipboardBridge {
    private val loaded = NativeLibraryLoader.load(LIBRARY_NAME, NativeX11ClipboardBridge::class.java)

    val isLoaded: Boolean get() = loaded

    /**
     * Probes for `libxcb` + `libxcb-xfixes` via `dlopen` and initializes the
     * hidden window + event thread. Returns `false` when either library is
     * missing or `$DISPLAY` is unset.
     */
    @JvmStatic
    external fun nativeInit(): Boolean

    /**
     * Monotonic counter bumped by the XFixes event handler whenever
     * `CLIPBOARD` changes ownership. Cheap — no X round trip.
     */
    @JvmStatic
    external fun nativeChangeCount(): Long

    /** Target atoms advertised by the current CLIPBOARD owner, as MIME-ish strings. */
    @JvmStatic
    external fun nativeAvailableTargets(): Array<String>

    @JvmStatic
    external fun nativeReadText(): String?

    @JvmStatic
    external fun nativeReadHtml(): String?

    @JvmStatic
    external fun nativeReadRtf(): String?

    @JvmStatic
    external fun nativeReadImagePng(): ByteArray?

    /**
     * Returns a single-char prefix (`"c"` for copy or `"m"` for move/cut) followed
     * by absolute POSIX paths decoded from `x-special/gnome-copied-files` when
     * available, or `text/uri-list` otherwise. Returns an empty array if no file
     * list is present.
     */
    @JvmStatic
    external fun nativeReadFilePaths(): Array<String>

    /**
     * Atomic write — registers the set of offered targets in one shot and takes
     * ownership of `CLIPBOARD`. The native side retains the payload for the
     * lifetime of the selection.
     */
    @JvmStatic
    external fun nativeWrite(
        text: String?,
        html: String?,
        rtf: String?,
        imagePng: ByteArray?,
        paths: Array<String>?,
        isCut: Boolean,
    ): Boolean

    /** Releases ownership of `CLIPBOARD` (best-effort SAVE_TARGETS handoff). */
    @JvmStatic
    external fun nativeClear(): Boolean
}
