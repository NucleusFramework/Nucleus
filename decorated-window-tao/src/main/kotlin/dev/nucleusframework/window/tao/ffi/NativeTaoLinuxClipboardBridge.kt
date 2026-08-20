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
 * Text crosses the boundary as UTF-8 **byte arrays**: `GetStringUTFChars`
 * produces modified UTF-8, which mangles every non-BMP character (emoji).
 *
 * Threading: every entry point must run on the GTK main thread (= Tao
 * event-loop thread = Compose's `Dispatchers.Main`).
 */
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
     * Receives the result of [nativeRequestTextUtf8]. Null = no text on the
     * clipboard. Implement with a named class rather than a lambda: the native
     * side resolves `onText` on the object's concrete class, which has to be
     * declared in `reachability-metadata.json` for native-image.
     */
    interface TextCallback {
        /** Called once, on the GTK main thread, with the UTF-8 encoded selection. */
        fun onText(utf8: ByteArray?)
    }

    @JvmStatic
    external fun nativeIsAvailable(): Boolean

    /** Takes ownership of the selection and serves [utf8] from this process. */
    @JvmStatic
    external fun nativeSetTextUtf8(utf8: ByteArray): Boolean

    /** Drops our ownership of the selection; no-op when another app owns it. */
    @JvmStatic
    external fun nativeClear()

    /**
     * Starts an asynchronous read. [callback] is invoked exactly once from the
     * GTK main loop — possibly *synchronously*, when this process owns the
     * selection. Returns false when the request never reached GTK, in which
     * case the callback will not fire at all.
     */
    @JvmStatic
    external fun nativeRequestTextUtf8(callback: TextCallback): Boolean

    /**
     * Synchronous read. GTK spins a nested main loop until the selection owner
     * answers, so this is reserved for the deprecated `ClipboardManager` path;
     * the suspending `Clipboard` API uses [nativeRequestTextUtf8].
     */
    @JvmStatic
    external fun nativeWaitForTextUtf8(): ByteArray?

    /** Whether the selection currently offers text. Also spins a nested main loop. */
    @JvmStatic
    external fun nativeHasText(): Boolean
}
