package io.github.kdroidfilter.nucleus.window.tao

import io.github.kdroidfilter.nucleus.core.runtime.NativeLibraryLoader

private const val LIBRARY_NAME = "nucleus_tao_linux_widget"

/**
 * JNI bridge to `linux/nucleus_tao_linux_widget.c`. Reparents and
 * positions a user-supplied `GtkWidget*` inside Tao's content widget
 * tree so the [NucleusPlatformView.GtkWidget] variant of [NativeView]
 * can mount things like a `WebKitWebView` directly into the Tao
 * window.
 *
 * Threading: every entry point must run on the GTK main thread (=
 * Tao event-loop thread = Compose dispatcher thread).
 */
internal object NativeTaoLinuxWidgetBridge {
    val isLoaded: Boolean = NativeLibraryLoader.load(
        LIBRARY_NAME,
        NativeTaoLinuxWidgetBridge::class.java,
    )

    /**
     * Reparents [widgetPtr] (a raw `GtkWidget*` cast to Long) into a
     * `GtkFixed` lazily injected inside Tao's content `GtkBox`. No-op
     * if Tao's content isn't a GtkBox (other layout backends would
     * need their own embedding path).
     */
    @JvmStatic
    external fun nativeAttach(gtkWindowPtr: Long, widgetPtr: Long)

    /** Removes [widgetPtr] from its current GTK parent. Safe to call twice. */
    @JvmStatic
    external fun nativeDetach(widgetPtr: Long)

    /**
     * Moves and resizes [widgetPtr]. Coordinates are in **logical
     * pixels** (i.e. dp on GTK 3) — caller must divide Compose
     * physical pixels by the GDK scale factor before calling.
     */
    @JvmStatic
    external fun nativeSetFrame(
        gtkWindowPtr: Long,
        widgetPtr: Long,
        xLogical: Int,
        yLogical: Int,
        widthLogical: Int,
        heightLogical: Int,
    )
}
