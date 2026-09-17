package dev.nucleusframework.window.tao.headful

import dev.nucleusframework.window.tao.ffi.NativeTaoBridge

/**
 * Thin wrapper around the Linux headful helper that synthesizes a
 * [GdkEventScroll](https://docs.gtk.org/gdk3/struct.EventScroll.html) and
 * delivers it through GTK's `scroll-event` signal.
 *
 * A real mouse wheel on GTK 3 arrives as `direction = UP/DOWN/LEFT/RIGHT`
 * with `delta_x = delta_y = 0`. Trackpads arrive as `SMOOTH` with a
 * populated delta. Stage-1 [dev.nucleusframework.window.tao.scene.TaoSceneScrollTest]
 * never sees that split because it injects a pre-shaped
 * [dev.nucleusframework.window.tao.TaoPointerScrollEvent] past the native
 * handler — which is why #533 was invisible to the scene battery.
 */
internal object LinuxGdkScrollProbe {
    const val UP: Int = 0
    const val DOWN: Int = 1
    const val LEFT: Int = 2
    const val RIGHT: Int = 3
    const val SMOOTH: Int = 4

    fun inject(
        handle: Long,
        direction: Int,
        deltaXMilli: Int = 0,
        deltaYMilli: Int = 0,
        x: Int,
        y: Int,
    ): Boolean =
        NativeTaoBridge.nativeLinuxInjectGdkScroll(
            handle,
            direction,
            deltaXMilli,
            deltaYMilli,
            x,
            y,
        )
}
