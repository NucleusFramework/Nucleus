package dev.nucleusframework.window.tao.ffi

/**
 * JNI bridge to the macOS direct touch forwarder (#892). The symbols live in
 * `nucleus_tao` (`platform/macos/touch.rs` over `macos/direct_touch.m`), which
 * [NativeTaoBridge] loads; the contacts reach the JVM through
 * [NativeTaoBridge.EventCallback.onTouchInput].
 */
internal object NativeTaoMacOsTouchBridge {
    /** Whether `nucleus_tao` is loaded; initialising this object loads it first. */
    val isLoaded: Boolean = NativeTaoBridge.isLoaded

    /**
     * Attaches the direct touch forwarder to [handle]'s content view, once.
     * From then on touchscreen and Sidecar contacts reach `onTouchInput`
     * instead of AppKit's mouse emulation. Event-loop thread only.
     */
    @JvmStatic
    external fun nativeAttachDirectTouch(handle: Long)

    /** E2E: whether [handle]'s content view carries the direct touch forwarder. Event-loop thread only. */
    @JvmStatic
    external fun nativeHasDirectTouch(handle: Long): Boolean
}
