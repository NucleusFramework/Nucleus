package dev.nucleusframework.window.tao.ffi

import dev.nucleusframework.core.runtime.NativeLibraryLoader

private const val LIBRARY_NAME = "nucleus_tao_linux_clipboard"

/**
 * JNI bridge to `linux/nucleus_tao_linux_clipboard.c`. Reads and writes the
 * GTK `CLIPBOARD` selection of the default display, i.e. the clipboard of
 * whichever GDK backend the Tao window actually runs on (Wayland or X11) —
 * unlike `java.awt.Toolkit.getSystemClipboard()`, which is always X11 on
 * Linux. See issue #582.
 *
 * Text, PNG images and `file://` URI lists all cross the boundary as **byte
 * arrays**: `GetStringUTFChars` produces modified UTF-8, which mangles every
 * non-BMP character (emoji), and images are binary to begin with. URI lists
 * and target lists are newline-separated UTF-8.
 *
 * Threading: every entry point must run on the GTK main thread (= Tao
 * event-loop thread = Compose's `Dispatchers.Main`).
 */
@Suppress("TooManyFunctions")
internal object NativeTaoLinuxClipboardBridge {
    val isLoaded: Boolean =
        NativeLibraryLoader.load(
            LIBRARY_NAME,
            NativeTaoLinuxClipboardBridge::class.java,
        )

    /**
     * True when GTK is loadable and a default `GdkDisplay` exists, i.e. when
     * the clipboard entry points below can do anything. False in a headless
     * process, on non-Linux, and when the helper library is missing.
     */
    val isAvailable: Boolean
        get() = isLoaded && nativeIsAvailable()

    /**
     * Receives the payload of an asynchronous read. Null = the clipboard has
     * nothing in that format. Implement with a named class rather than a
     * lambda: the native side resolves `onBytes` on the object's concrete
     * class, which has to be declared in `reachability-metadata.json` for
     * native-image.
     */
    interface BytesCallback {
        /** Called once, on the GTK main thread. */
        fun onBytes(bytes: ByteArray?)
    }

    @JvmStatic
    external fun nativeIsAvailable(): Boolean

    // ── Write ───────────────────────────────────────────────────────────

    /** Takes ownership of the selection and serves [utf8] from this process. */
    @JvmStatic
    external fun nativeSetTextUtf8(utf8: ByteArray): Boolean

    /**
     * Publishes [png] as an image. GdkPixbuf decodes it once natively, which
     * is what lets GTK offer (and convert to) `image/bmp`, `image/jpeg` and
     * `image/tiff` as well.
     */
    @JvmStatic
    external fun nativeSetImagePng(png: ByteArray): Boolean

    /**
     * Publishes newline-separated `file://` URIs as `text/uri-list` plus
     * `x-special/gnome-copied-files`, the target GTK file managers paste from.
     */
    @JvmStatic
    external fun nativeSetUriListUtf8(uriList: ByteArray): Boolean

    /** Drops our ownership of the selection; no-op when another app owns it. */
    @JvmStatic
    external fun nativeClear()

    // ── Asynchronous reads ──────────────────────────────────────────────
    // Each invokes [callback] exactly once from the GTK main loop — possibly
    // *synchronously*, when this process owns the selection. A false return
    // means the request never reached GTK and the callback will not fire.

    @JvmStatic
    external fun nativeRequestTextUtf8(callback: BytesCallback): Boolean

    /** Whatever image the clipboard holds, re-encoded to PNG by GdkPixbuf. */
    @JvmStatic
    external fun nativeRequestImagePng(callback: BytesCallback): Boolean

    @JvmStatic
    external fun nativeRequestUriListUtf8(callback: BytesCallback): Boolean

    /**
     * The MIME names the selection currently advertises, newline-separated.
     * Cheap next to the content itself, so it is what decides which flavors a
     * clip entry exposes.
     */
    @JvmStatic
    external fun nativeRequestTargetsUtf8(callback: BytesCallback): Boolean

    // ── Synchronous reads ───────────────────────────────────────────────
    // GTK spins a nested main loop until the owner answers. Only legal on the
    // GTK main thread, and only for callers that cannot suspend: a
    // `Transferable` read from arbitrary code, or the deprecated
    // `ClipboardManager`.

    @JvmStatic
    external fun nativeWaitForTextUtf8(): ByteArray?

    @JvmStatic
    external fun nativeWaitForImagePng(): ByteArray?

    @JvmStatic
    external fun nativeWaitForUriListUtf8(): ByteArray?

    /** Whether the selection currently offers text. Also spins a nested main loop. */
    @JvmStatic
    external fun nativeHasText(): Boolean
}
