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

    /**
     * Releases GTK's focused widget on [gtkWindowPtr] (`gtk_window_set_focus(NULL)`).
     * Kept for API completeness; the GtkEventBox-based overlay path
     * makes this redundant in practice (the EventBox grabs focus on
     * press, replacing the WebView-eats-keys problem this used to
     * fix).
     */
    @JvmStatic
    external fun nativeRequestKeyboardFocus(gtkWindowPtr: Long)

    /**
     * Creates an invisible `GtkEventBox` overlay child inside the
     * GtkOverlay we inject into Tao's content `GtkBox`, positioned
     * via the `get-child-position` signal. Returns the EventBox
     * pointer (cast to Long) — pass it to [nativeMoveInputBox] /
     * [nativeRemoveInputBox]. Returns 0 on failure (e.g. Tao's
     * content isn't a `GtkBox`, or the GTK lib didn't load).
     *
     * Each EventBox added is stacked **above** previously added
     * overlay children in z-order (matches `gtk_overlay_add_overlay`
     * semantics), so it captures clicks for its rect even when an
     * embedded user widget (e.g. `WebKitWebView`) is positioned at
     * the same location. The event isn't consumed — `button-press-event`
     * bubbles up to the GtkApplicationWindow where Tao's window-level
     * handler picks it up and forwards to the Compose scene.
     */
    @JvmStatic
    external fun nativeAddInputBox(gtkWindowPtr: Long): Long

    /**
     * Repositions an EventBox previously created by [nativeAddInputBox].
     * Coords in **logical** GTK pixels (Compose physical / scale).
     */
    @JvmStatic
    external fun nativeMoveInputBox(
        boxPtr: Long,
        xLogical: Int,
        yLogical: Int,
        widthLogical: Int,
        heightLogical: Int,
    )

    /** Destroys an EventBox previously created by [nativeAddInputBox]. */
    @JvmStatic
    external fun nativeRemoveInputBox(boxPtr: Long)


    /**
     * Receives motion / press / release events forwarded from the
     * native EventBox handlers. Coords are **logical pixels** in
     * GtkApplicationWindow space (already translated via
     * `gtk_widget_translate_coordinates`).
     *
     * `type`: 0 = move, 1 = press, 2 = release.
     * `pressed`: 1 if currently pressed, 0 otherwise.
     */
    interface OverlayInputCallback {
        @Suppress("FunctionParameterNaming")
        fun onEvent(type: Int, xLogical: Int, yLogical: Int, button: Int, pressed: Int)
    }

    /**
     * Registers a callback to receive overlay input events from the
     * EventBox associated with [boxPtr]. Pass null to clear. The
     * callback is invoked synchronously from GTK's main thread inside
     * the signal handlers, *before* the event would have bubbled up
     * to Tao — so updating Compose's last cursor position here
     * guarantees subsequent click hit-testing lands on the right
     * widget. Necessary on Linux because Tao's
     * `cursor.window_at_position()` returns EventBox-local coords
     * when WebKit's accelerated subsurface has the seat focus.
     */
    @JvmStatic
    external fun nativeSetInputBoxCallback(boxPtr: Long, callback: OverlayInputCallback?)
}
