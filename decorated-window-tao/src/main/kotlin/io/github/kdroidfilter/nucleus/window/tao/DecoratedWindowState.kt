package io.github.kdroidfilter.nucleus.window.tao

/**
 * Snapshot of the runtime state of a Tao decorated window. Matches the bit
 * layout of `decorated-window-core`'s `DecoratedWindowState` so call-site
 * patterns (`state.isFullscreen`, `state.isMaximized`, etc.) are identical.
 */
@JvmInline
value class DecoratedWindowState(val state: ULong) {
    val isActive: Boolean get() = state and Active != 0UL
    val isFullscreen: Boolean get() = state and Fullscreen != 0UL
    val isMinimized: Boolean get() = state and Minimized != 0UL
    val isMaximized: Boolean get() = state and Maximized != 0UL

    fun copy(
        active: Boolean = isActive,
        fullscreen: Boolean = isFullscreen,
        minimized: Boolean = isMinimized,
        maximized: Boolean = isMaximized,
    ): DecoratedWindowState = of(active, fullscreen, minimized, maximized)

    companion object {
        val Active: ULong = 1UL shl 0
        val Fullscreen: ULong = 1UL shl 1
        val Minimized: ULong = 1UL shl 2
        val Maximized: ULong = 1UL shl 3

        fun of(
            active: Boolean = true,
            fullscreen: Boolean = false,
            minimized: Boolean = false,
            maximized: Boolean = false,
        ): DecoratedWindowState =
            DecoratedWindowState(
                (if (active) Active else 0UL) or
                    (if (fullscreen) Fullscreen else 0UL) or
                    (if (minimized) Minimized else 0UL) or
                    (if (maximized) Maximized else 0UL),
            )
    }
}
