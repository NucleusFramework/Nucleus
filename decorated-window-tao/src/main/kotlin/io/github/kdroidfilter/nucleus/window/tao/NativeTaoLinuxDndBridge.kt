package io.github.kdroidfilter.nucleus.window.tao

import io.github.kdroidfilter.nucleus.core.runtime.NativeLibraryLoader

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
     * Synchronous outbound DnD via `gtk_drag_begin_with_coordinates`. The
     * native side cooperatively pumps `gtk::main_iteration_do(true)` until
     * `drag-end` (or `drag-failed`) fires, then returns the negotiated drop
     * effect. Must run on the GTK main thread (= Tao event-loop thread).
     */
    @JvmStatic
    external fun nativeStartDrag(
        handle: Long,
        files: Array<String>?,
        text: String?,
        allowedEffects: Int,
    ): Int

    const val DROP_EFFECT_NONE: Int = 0
    const val DROP_EFFECT_COPY: Int = 1
    const val DROP_EFFECT_MOVE: Int = 2
    const val DROP_EFFECT_LINK: Int = 4
}
