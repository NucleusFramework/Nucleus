package dev.nucleusframework.window.tao.ffi

import dev.nucleusframework.core.runtime.NativeLibraryLoader

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
@Suppress("TooManyFunctions")
internal object NativeTaoLinuxWidgetBridge {
    val isLoaded: Boolean =
        NativeLibraryLoader.load(
            LIBRARY_NAME,
            NativeTaoLinuxWidgetBridge::class.java,
        )

    /**
     * Loads GTK through the same `RTLD_LOCAL` dlopen path as every other
     * entry point and returns its runtime version (e.g. "3.24.49"), or null
     * when GTK is unavailable. Probe for the issue-#366 regression test:
     * proves GTK was dlopen-ed and is functional in this process.
     */
    @JvmStatic
    external fun nativeGtkVersion(): String?

    /**
     * Registers [widgetPtr] (a raw `GtkWidget*` cast to Long) for
     * embedding into a `GtkOverlay` lazily injected inside Tao's
     * content `GtkBox`. The actual mount happens on the first
     * [nativeSetFrame] with a real rect, so the widget realizes
     * directly at its final size. No-op if Tao's content isn't a
     * GtkBox (other layout backends would need their own embedding
     * path).
     */
    @JvmStatic
    external fun nativeAttach(
        gtkWindowPtr: Long,
        widgetPtr: Long,
    )

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
     * native EventBox handlers. Coords are **logical pixels** in the
     * window content area (bin child), matching Tao's CSD-normalised
     * pointer path and Compose's (0,0) — not the decorated toplevel
     * (which includes theme shadow margins under hidden-titlebar CSD).
     *
     * `type`: 0 = move, 1 = press, 2 = release.
     * `pressed`: 1 if currently pressed, 0 otherwise.
     */
    interface OverlayInputCallback {
        @Suppress("FunctionParameterNaming")
        fun onEvent(
            type: Int,
            xLogical: Int,
            yLogical: Int,
            button: Int,
            pressed: Int,
        )

        /** Widget-content logical pixels; [dx]/[dy] are GTK scroll deltas. */
        fun onScroll(
            xLogical: Int,
            yLogical: Int,
            dx: Float,
            dy: Float,
        ) {
        }
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
    external fun nativeSetInputBoxCallback(
        boxPtr: Long,
        callback: OverlayInputCallback?,
    )

    /**
     * Forwards the live GDK pointer event captured by the EventBox onto
     * [widgetPtr], retargeted to widget-local logical pixels. [type]:
     * 1 down, 2 up, 3 move. Used to redispatch Compose-unconsumed hits
     * to the embedded GTK widget after interop blending captured them.
     * No-op outside an EventBox signal callback — GdkEvents are never
     * synthesised (a device-less event crashes WebKit).
     */
    @JvmStatic
    external fun nativeDispatchPointer(
        widgetPtr: Long,
        type: Int,
        xLogical: Int,
        yLogical: Int,
        button: Int,
        pressed: Boolean,
    )

    /**
     * Forwards the live GDK scroll event captured by the EventBox onto
     * [widgetPtr] at widget-local logical pixels. No-op outside an
     * EventBox scroll callback (never synthesised).
     */
    @JvmStatic
    external fun nativeDispatchScroll(
        widgetPtr: Long,
        xLogical: Int,
        yLogical: Int,
        dx: Float,
        dy: Float,
    )

    /**
     * Gives the keyboard back to Compose after a press Compose kept: clears
     * the GTK focus widget when it is an embed (not one of the suite's own
     * input boxes), so keys route to Tao's toplevel handler again. `true`
     * when it did.
     */
    @JvmStatic
    external fun nativeClaimKeyboardForCompose(gtkWindowPtr: Long): Boolean

    /**
     * GDK's live pointer button mask (`GDK_BUTTON1_MASK = 1 shl 8`,
     * `GDK_BUTTON3_MASK = 1 shl 10`, …), or -1 when unavailable.
     */
    @JvmStatic
    external fun nativeQueryPointerButtons(gtkWindowPtr: Long): Int

    /** `gtk_widget_queue_draw` on the toplevel: GTK paints and commits it on its next frame. */
    @JvmStatic
    external fun nativeQueueToplevelDraw(gtkWindowPtr: Long)

    // ── Diagnostics for the headful suite ─────────────────────────────

    /**
     * A fresh, unparented `GtkEntry` for a headful case to embed through
     * `NativeView` — the test module cannot fabricate a `GtkWidget*` on
     * its own. 0 when GTK is unavailable. Destroy with
     * [nativeDiagDestroyWidget].
     */
    @JvmStatic
    external fun nativeDiagCreateEntry(): Long

    /** Detaches and destroys a widget from [nativeDiagCreateEntry]. */
    @JvmStatic
    external fun nativeDiagDestroyWidget(widgetPtr: Long)

    /** The widget [gtkWindowPtr] routes keys to (`gtk_window_get_focus`), or 0. */
    @JvmStatic
    external fun nativeDiagFocusWidget(gtkWindowPtr: Long): Long

    /** Whether [widgetPtr] itself holds GTK focus. */
    @JvmStatic
    external fun nativeDiagWidgetHasFocus(widgetPtr: Long): Boolean

    /** The text of an entry from [nativeDiagCreateEntry], or null. */
    @JvmStatic
    external fun nativeDiagEntryText(widgetPtr: Long): String?

    /**
     * Where a widget sits, in Tao's content-box coordinates and logical px,
     * as `[x, y, w, h]` — null while it is not mapped.
     */
    @JvmStatic
    external fun nativeDiagWidgetFrame(
        gtkWindowPtr: Long,
        widgetPtr: Long,
    ): IntArray?
}
