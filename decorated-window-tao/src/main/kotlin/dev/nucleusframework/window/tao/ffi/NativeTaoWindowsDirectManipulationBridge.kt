package dev.nucleusframework.window.tao.ffi

/**
 * JNI bridge to the Windows DirectManipulation viewports (#706). The symbols
 * live in `nucleus_tao` (`platform/windows/direct_manipulation.rs`), which
 * [NativeTaoBridge] loads; the viewport's stream reaches the JVM through
 * [NativeTaoBridge.EventCallback.onDirectManipulation].
 */
internal object NativeTaoWindowsDirectManipulationBridge {
    /** Whether `nucleus_tao` is loaded; initialising this object loads it first. */
    val isLoaded: Boolean = NativeTaoBridge.isLoaded

    /**
     * Whether new windows bind a DirectManipulation viewport. Read by the
     * native side at window creation; set before the loop starts.
     */
    @JvmStatic
    external fun nativeSetDirectManipulationEnabled(enabled: Boolean)

    /**
     * Binds [handle]'s viewport to the render-surface child [hwnd], replacing
     * its previous target. Event-loop thread only.
     */
    @JvmStatic
    external fun nativeBindInputWindow(
        handle: Long,
        hwnd: Long,
    )

    /** The viewport's input HWND and its parent HWND, or null when unattached. Event-loop thread only. */
    @JvmStatic
    external fun nativeDiagDirectManipulationInputWindow(handle: Long): LongArray?

    /** Whether [handle]'s window has a live viewport. Event-loop thread only. */
    @JvmStatic
    external fun nativeDirectManipulationAttached(handle: Long): Boolean

    /**
     * Diagnostics for the e2e: `[attached, hitTests, claimedContacts, status,
     * deliveredEvents, resetting, processHitTests]`, or null. Event-loop
     * thread only.
     */
    @JvmStatic
    external fun nativeDiagDirectManipulationStats(handle: Long): IntArray?

    /**
     * Test hook: mouse wheel notches are also handed to the viewports (the
     * mouse-focus contact + `ProcessInput`), so DirectManipulation animates a
     * manipulation of its own a test can start. `false` (and no effect)
     * unless the process runs with `NUCLEUS_TAO_INPUT_INJECTION=1`.
     */
    @JvmStatic
    external fun nativeDiagDirectManipulationWheelFocus(enabled: Boolean): Boolean

    /**
     * Test hook: queues [events] on [handle]'s viewport as if its event
     * handler had reported them — `[kind, status, previousStatus, scale, x, y,
     * focalX, focalY]` each — for the loop to deliver through the real pump
     * and wire. A precision-touchpad contact cannot be injected, so this is
     * how an e2e plays a touchpad's stream. `false` unless injection is armed
     * and the window has a viewport. Event-loop thread only.
     */
    @JvmStatic
    external fun nativeDiagDirectManipulationReplay(
        handle: Long,
        events: FloatArray,
    ): Boolean
}
