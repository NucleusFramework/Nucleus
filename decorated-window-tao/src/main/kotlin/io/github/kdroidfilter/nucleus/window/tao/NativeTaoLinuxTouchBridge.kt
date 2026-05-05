package io.github.kdroidfilter.nucleus.window.tao

import io.github.kdroidfilter.nucleus.core.runtime.NativeLibraryLoader

/**
 * JNI bridge to the Linux touch + touchpad-gesture helper for the Tao backend.
 *
 * Implements Stage 1 of `TOUCH_PLAN.md` on Linux: touchscreen multi-touch
 * (Wayland + X11/XWayland) and trackpad pinch/rotate (Wayland-only — see
 * `TOUCH_LINUX_RESEARCH_RESPONSE.md` and Mozilla bug 1581126 for why GTK 3
 * doesn't expose touchpad gestures on its X11 backend).
 *
 * Same shape as [NativeTaoLinuxDndBridge]: per-window install/revoke from
 * the Tao event-loop thread (= GTK main thread = Compose dispatcher
 * thread); the native side holds a `GlobalRef` on the callback object until
 * revoke is called.
 *
 * Wire format:
 *
 * - **Touch**: every event sends the *full* set of currently-down fingers
 *   so the JVM side can call `ComposeScene.sendPointerEvent(eventType,
 *   pointers = list)` directly — Compose treats absence as release. The
 *   released-this-event finger is still present in the array with its bit
 *   cleared in [Callback.onTouchEvent.pressedMask], so the JVM can emit a
 *   single `Release` carrying the final position.
 * - **Trackpad gesture**: matches [NativeTaoBridge.EventCallback.onTrackpadGesture]
 *   exactly — same kind / phase / fixed-point scaling. The Rust side has
 *   already converted GDK's absolute pinch scale into per-event ratio
 *   deltas and GDK's radian angle deltas into degrees, so the JVM-side
 *   synth math is platform-independent.
 *
 * Coordinates passed to [Callback.onTouchEvent] are physical pixels in the
 * GtkWindow's bin-child coordinate space, encoded as fixed-point ×1024
 * — the same encoding the rest of the Tao pointer pipeline uses
 * ([events.rs::CURSOR_FIXED_SCALE]).
 */
internal object NativeTaoLinuxTouchBridge {
    private const val LIBRARY_NAME = "nucleus_tao"

    private val loaded = NativeLibraryLoader.load(LIBRARY_NAME, NativeTaoLinuxTouchBridge::class.java)

    val isLoaded: Boolean get() = loaded

    interface Callback {
        /**
         * Multi-touch update.
         *
         * @param handle   Tao window handle.
         * @param eventType One of [TaoTouchEvent.PRESS] / [MOVE] / [RELEASE] / [CANCEL].
         * @param count    Length of [ids] / [xsFixed] / [ysFixed] (currently-down
         *                 fingers + the one released-this-event when applicable).
         * @param ids      Stable per-finger ids. Suitable for use as
         *                 `ComposeScenePointer.id` directly.
         * @param xsFixed  Per-finger X in physical pixels × 1024.
         * @param ysFixed  Per-finger Y in physical pixels × 1024.
         * @param pressedMask Bit `i` set iff `ids[i]` is currently pressed. The
         *                 finger being released this event has its bit cleared
         *                 even though it is still present in the arrays.
         */
        @Suppress("LongParameterList", "FunctionParameterNaming")
        fun onTouchEvent(
            handle: Long,
            eventType: Int,
            count: Int,
            ids: LongArray,
            xsFixed: LongArray,
            ysFixed: LongArray,
            pressedMask: Long,
        )

        /**
         * Trackpad pinch / rotate. Same wire format as
         * [NativeTaoBridge.EventCallback.onTrackpadGesture] so the JVM-side
         * synth math (`TaoComposeSceneHost.onTrackpadGesture`) is reused
         * verbatim across macOS and Linux. Wayland-only on Linux.
         */
        @Suppress("LongParameterList", "FunctionParameterNaming")
        fun onTrackpadGesture(
            handle: Long,
            kind: Int,
            phase: Int,
            xFixed: Long,
            yFixed: Long,
            valueFixed: Long,
        )
    }

    @JvmStatic
    external fun nativeRegister(
        handle: Long,
        callback: Callback,
    ): Int

    @JvmStatic
    external fun nativeRevoke(handle: Long): Int
}

/**
 * Touch phase reported by [NativeTaoLinuxTouchBridge.Callback.onTouchEvent]
 * (Linux multi-finger snapshot path) and [NativeTaoBridge.EventCallback.onTouchInput]
 * (Windows per-finger Tao path). Values are shared so JVM-side hosts can map
 * them with a single `when`.
 */
@Suppress("MagicNumber")
object TaoTouchEvent {
    const val PRESS: Int = 0
    const val MOVE: Int = 1
    const val RELEASE: Int = 2
    const val CANCEL: Int = 3

    /**
     * Sentinel `forceFixed` value meaning the digitizer doesn't expose
     * pressure (most consumer touchscreens). Distinguishable from a true
     * 0 reading.
     */
    const val FORCE_UNKNOWN: Int = -1
}
