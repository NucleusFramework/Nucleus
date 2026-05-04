package io.github.kdroidfilter.nucleus.window.tao

import io.github.kdroidfilter.nucleus.core.runtime.NativeLibraryLoader

/**
 * JNI bridge to the macOS NSDraggingDestination / NSDraggingSource helper for
 * the Tao backend (`macos/dnd.m`, shipped as `libnucleus_tao_dnd.dylib`).
 *
 * Mirrors [NativeTaoWindowsDndBridge]: same callback shape, same drop-effect
 * constants. The first parameter of every callback is the NSView pointer (not
 * an HWND) — opaque Long passed through unchanged for diagnostic purposes.
 *
 * Lifecycle: [nativeRegister] from the Tao event-loop thread (= macOS main
 * thread = Compose dispatcher thread) once the NSView is realised;
 * [nativeRevoke] before the window is destroyed. The native side holds a
 * GlobalRef on the callback object until revoke is called.
 */
internal object NativeTaoMacOsDndBridge {
    private const val LIBRARY_NAME = "nucleus_tao_dnd"

    private val loaded = NativeLibraryLoader.load(LIBRARY_NAME, NativeTaoMacOsDndBridge::class.java)

    val isLoaded: Boolean get() = loaded

    /**
     * Receives raw NSDraggingDestination callbacks. All callbacks fire on the
     * macOS main thread. [x]/[y] are in physical pixels, top-left origin
     * (already converted from the NSView's coordinate system).
     *
     * Return values for `onDragEnter`/`onDragOver`/`onDrop`:
     *   - [DROP_EFFECT_NONE] — reject the drag/drop
     *   - [DROP_EFFECT_COPY] — accept as a copy
     */
    interface Callback {
        @Suppress("FunctionParameterNaming")
        fun onDragEnter(nsView: Long, x: Int, y: Int, modState: Int, hasFiles: Boolean): Int

        @Suppress("FunctionParameterNaming")
        fun onDragOver(nsView: Long, x: Int, y: Int, modState: Int, hasFiles: Boolean): Int

        fun onDragLeave(nsView: Long)

        @Suppress("FunctionParameterNaming")
        fun onDrop(nsView: Long, x: Int, y: Int, modState: Int, files: Array<String>?): Int
    }

    @JvmStatic
    external fun nativeRegister(nsView: Long, callback: Callback): Int

    @JvmStatic
    external fun nativeRevoke(nsView: Long): Int

    /**
     * Starts an outbound OS drag session via
     * `[NSView beginDraggingSessionWithItems:event:source:]`.
     *
     * Synchronous: the native side pumps the AppKit run loop until the user
     * drops or cancels, then returns the negotiated drop effect. Must run on
     * the macOS main thread (= Tao event-loop thread).
     *
     * Either [files] or [text] (or both) must be non-null/non-empty.
     *
     * @param allowedEffects bitmask of [DROP_EFFECT_COPY] / [DROP_EFFECT_MOVE] /
     *   [DROP_EFFECT_LINK]
     * @return one of [DROP_EFFECT_NONE]/[DROP_EFFECT_COPY]/[DROP_EFFECT_MOVE]/[DROP_EFFECT_LINK]
     */
    @JvmStatic
    external fun nativeStartDrag(
        nsView: Long,
        files: Array<String>?,
        text: String?,
        allowedEffects: Int,
    ): Int

    const val DROP_EFFECT_NONE: Int = 0
    const val DROP_EFFECT_COPY: Int = 1
    const val DROP_EFFECT_MOVE: Int = 2
    const val DROP_EFFECT_LINK: Int = 4
}
