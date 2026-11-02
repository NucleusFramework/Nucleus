package dev.nucleusframework.window.tao.ffi

/**
 * JNI bridge to the identity GTK gives every Linux window: the Wayland app_id and the X11
 * `WM_CLASS`. The symbols live in `nucleus_tao` (`window_jni.rs`), which [NativeTaoBridge] loads.
 */
internal object NativeTaoLinuxIdentityBridge {
    /** Whether `nucleus_tao` is loaded; initialising this object loads it first. */
    val isLoaded: Boolean = NativeTaoBridge.isLoaded

    /**
     * Linux: the app_id GTK sends for every window on a Wayland display (`g_set_prgname`, so
     * the compositor pairs the windows with the `.desktop` entry of that name). Call before
     * [NativeTaoBridge.nativeRunBlocking]. Ignored on X11, where [nativeSetX11WmClass] applies instead.
     */
    @JvmStatic
    external fun nativeSetWaylandAppId(appId: String)

    /**
     * Linux: the `WM_CLASS` GTK gives every window on an X11 display, both its instance
     * (`g_set_prgname`) and its class (`gdk_set_program_class`), so the desktop pairs the
     * windows with the `.desktop` entry whose `StartupWMClass` it is. Call before
     * [NativeTaoBridge.nativeRunBlocking]. Ignored on Wayland.
     */
    @JvmStatic
    external fun nativeSetX11WmClass(wmClass: String)
}
