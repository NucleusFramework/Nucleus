package dev.nucleusframework.window.tao.ffi

import dev.nucleusframework.core.runtime.NativeLibraryLoader

/**
 * JNI bridge to the Windows IDropTarget COM helper for the Tao backend.
 *
 * Lifecycle: [nativeRegister] from the Tao event-loop thread (= the Compose
 * dispatcher thread) once the HWND is known; [nativeRevoke] before the
 * window is destroyed. The native side holds a global reference on the
 * callback object until revoke is called.
 */
internal object NativeTaoWindowsDndBridge {
    private const val LIBRARY_NAME = "nucleus_tao_dnd"

    private val loaded = NativeLibraryLoader.load(LIBRARY_NAME, NativeTaoWindowsDndBridge::class.java)

    val isLoaded: Boolean get() = loaded

    /**
     * Receives raw IDropTarget callbacks. All callbacks fire on the thread
     * that owns the HWND (= Tao event-loop thread). [hwnd] is forwarded
     * unchanged for convenience; [x]/[y] are client-area coordinates in
     * physical pixels (top-left origin, after WM_NCCALCSIZE strip-off).
     *
     * Return values for `onDragEnter`/`onDragOver`/`onDrop`:
     *   - [DROP_EFFECT_NONE] — reject the drag/drop
     *   - [DROP_EFFECT_COPY] — accept as a copy
     */
    interface Callback {
        @Suppress("FunctionParameterNaming")
        fun onDragEnter(
            hwnd: Long,
            x: Int,
            y: Int,
            keyState: Int,
            hasFiles: Boolean,
        ): Int

        @Suppress("FunctionParameterNaming")
        fun onDragOver(
            hwnd: Long,
            x: Int,
            y: Int,
            keyState: Int,
            hasFiles: Boolean,
        ): Int

        fun onDragLeave(hwnd: Long)

        @Suppress("FunctionParameterNaming")
        fun onDrop(
            hwnd: Long,
            x: Int,
            y: Int,
            keyState: Int,
            files: Array<String>?,
        ): Int
    }

    @JvmStatic
    external fun nativeRegister(
        hwnd: Long,
        callback: Callback,
    ): Int

    @JvmStatic
    external fun nativeRevoke(hwnd: Long): Int

    /**
     * Keeps the host alive while `DoDragDrop` owns the event-loop thread.
     *
     * [pump] is upcalled from `IDropSource::QueryContinueDrag`, i.e. on every
     * mouse/keyboard state change for the duration of the drag, on the Tao
     * event-loop thread. Implementations must drain the main dispatcher and
     * paint one frame — and must not block, since the OS drag loop is waiting
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
     * Starts an outbound OS drag session via Win32 `DoDragDrop`.
     *
     * Blocks the calling thread (Win32 pumps its own modal message loop) until
     * the user drops or cancels. Must run on the thread owning [hwnd]
     * (= the Tao event-loop thread) — `DoDragDrop` takes the mouse capture on
     * the calling thread, so a thread that owns no window never sees the
     * mouse-up and leaves the OS drag session wedged system-wide.
     *
     * Either [files] or [text] (or both) must be non-null/non-empty.
     *
     * @param allowedEffects bitmask of [DROP_EFFECT_COPY] / [DROP_EFFECT_MOVE] /
     *   [DROP_EFFECT_LINK]
     * @param pump invoked repeatedly during the drag so the frozen event loop
     *   can still drain and render; see [DragPump]. `null` disables it.
     * @return one of [DROP_EFFECT_*][DROP_EFFECT_NONE] — the action the
     *   destination accepted, or [DROP_EFFECT_NONE] if cancelled.
     */
    @JvmStatic
    external fun nativeStartDrag(
        hwnd: Long,
        files: Array<String>?,
        text: String?,
        allowedEffects: Int,
        pump: DragPump?,
    ): Int

    const val DROP_EFFECT_NONE: Int = 0
    const val DROP_EFFECT_COPY: Int = 1
    const val DROP_EFFECT_MOVE: Int = 2
    const val DROP_EFFECT_LINK: Int = 4
}
