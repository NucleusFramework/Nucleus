package dev.nucleusframework.window.tao.ffi

import dev.nucleusframework.core.runtime.NativeLibraryLoader

/**
 * JNI bridge to the Linux DnD helper for the Tao backend.
 *
 * Mirrors [NativeTaoWindowsDndBridge] / [NativeTaoMacOsDndBridge] 1:1: same
 * callback shape, same drop-effect constants. The first parameter of every
 * callback is the Tao window handle (the same handle used by every other
 * `NativeTaoBridge` JNI call) — opaque Long passed through unchanged.
 *
 * Lifecycle: [nativeRegister] from the Tao event-loop thread (= GTK main
 * thread = Compose dispatcher thread) once the GtkWindow is realised;
 * [nativeRevoke] before the window is destroyed. The native side holds a
 * GlobalRef on the callback object until revoke is called.
 *
 * Unlike the Windows / macOS bridges, the Linux implementation lives inside
 * the main `nucleus_tao` Rust crate (not a sibling library): the `gtk = 0.18`
 * crate is already pulled in for cursor / handle / decoration helpers, and
 * Rust `cdylib` JNI exports are not affected by `strip = "symbols"`.
 *
 * Coordinates passed to the callback are **physical pixels** in the
 * GtkWindow's bin-child coordinate space — the same space the Tao pointer
 * pipeline operates in (post-translation via `gtk_widget_translate_coordinates`
 * + `gdk_window_get_scale_factor` on the native side).
 */
internal object NativeTaoLinuxDndBridge {
    private const val LIBRARY_NAME = "nucleus_tao"

    private val loaded = NativeLibraryLoader.load(LIBRARY_NAME, NativeTaoLinuxDndBridge::class.java)

    val isLoaded: Boolean get() = loaded

    interface Callback {
        @Suppress("FunctionParameterNaming")
        fun onDragEnter(
            handle: Long,
            x: Int,
            y: Int,
            modState: Int,
            hasFiles: Boolean,
        ): Int

        @Suppress("FunctionParameterNaming")
        fun onDragOver(
            handle: Long,
            x: Int,
            y: Int,
            modState: Int,
            hasFiles: Boolean,
        ): Int

        fun onDragLeave(handle: Long)

        @Suppress("FunctionParameterNaming")
        fun onDrop(
            handle: Long,
            x: Int,
            y: Int,
            modState: Int,
            files: Array<String>?,
        ): Int
    }

    @JvmStatic
    external fun nativeRegister(
        handle: Long,
        callback: Callback,
    ): Int

    @JvmStatic
    external fun nativeRevoke(handle: Long): Int

    /**
     * Keeps the host alive while an outbound drag session owns the GTK main
     * thread.
     *
     * [pump] is upcalled ~120×/s off a `glib` timeout that [nativeStartDrag]
     * schedules for the session's lifetime, on the GTK main thread (= Tao
     * event-loop thread). Implementations must drain the main dispatcher and
     * paint a frame — and must not block, since the drag's GTK pump is waiting
     * on the return.
     *
     * Must be a named class, not a lambda or anonymous object: GraalVM's
     * `GetMethodID` does not pick up inherited interface methods on anonymous
     * classes (same constraint as [Callback]).
     */
    interface DragPump {
        fun pump()
    }

    /**
     * Synchronous outbound DnD via `gtk_drag_begin_with_coordinates`. The
     * native side cooperatively pumps `gtk::main_iteration_do(true)` until
     * `drag-end` (or `drag-failed`) fires, then returns the negotiated drop
     * effect. Must run on the GTK main thread (= Tao event-loop thread).
     *
     * That GTK pump dispatches GDK events but never re-enters tao's event
     * loop — this call is made from inside one of its callbacks — so without
     * [pump] the host paints nothing for the whole session.
     *
     * @param privateData an in-process payload offered under
     *   [dev.nucleusframework.window.tao.dnd.TaoPrivateTransfer.MIME] to this
     *   application's own windows only (`SAME_APP`), or `null`. A session may
     *   carry it alone: the cross-window gestures ride the DnD session on
     *   native Wayland with nothing a foreign target could take.
     * @param iconArgb the drag icon under the pointer as premultiplied ARGB
     *   (`0xAARRGGBB`) device pixels, row-major, `iconWidth × iconHeight`;
     *   `null` for GTK's default icon. [iconScale] is the device pixels per
     *   logical pixel it was rendered at, [iconHotX] / [iconHotY] the pointer's
     *   position inside it in device pixels.
     * @param pump invoked repeatedly during the drag so the suppressed Tao tick
     *   can still drain and render; see [DragPump]. `null` disables it.
     */
    @Suppress("LongParameterList")
    @JvmStatic
    external fun nativeStartDrag(
        handle: Long,
        files: Array<String>?,
        text: String?,
        privateData: String?,
        allowedEffects: Int,
        iconArgb: IntArray?,
        iconWidth: Int,
        iconHeight: Int,
        iconScale: Float,
        iconHotX: Int,
        iconHotY: Int,
        pump: DragPump?,
    ): Int

    const val DROP_EFFECT_NONE: Int = 0
    const val DROP_EFFECT_COPY: Int = 1
    const val DROP_EFFECT_MOVE: Int = 2
    const val DROP_EFFECT_LINK: Int = 4
}
