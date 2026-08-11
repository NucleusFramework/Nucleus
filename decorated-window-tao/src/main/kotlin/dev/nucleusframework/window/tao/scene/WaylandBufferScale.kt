package dev.nucleusframework.window.tao.scene

/**
 * Rounds a physical pixel size **up** to the next multiple of [bufferScale].
 *
 * Every Wayland surface we render into announces
 * `wl_surface.set_buffer_scale(bufferScale)` so the compositor reads our
 * `logical × scale` pixel buffer as `logical` surface units. The protocol then
 * requires the attached buffer to be an integer multiple of that scale:
 * committing anything else is a fatal `wl_surface.invalid_size` error
 * (Mutter: *"Buffer size (101x61) must be an integer multiple of the
 * buffer_scale (2)"*), which kills the client. Compose popup bounds are
 * arbitrary physical pixels — a text-measured width or a `.5.dp` padding
 * lands on an odd number about half the time on a 200% output — so every
 * surface size has to be aligned before it reaches `wl_egl_window`.
 *
 * Rounding **up** (never down: a 1 px popup would collapse to 0) grows the
 * surface by at most `bufferScale - 1` px. The content is drawn from the
 * top-left and the surface is cleared transparent, so the extra edge is
 * invisible — the same trade GDK makes for its own popup buffers (a 51 × 31
 * logical popup gets a 102 × 62 shm buffer at scale 2).
 *
 * Also applied on X11, where the alignment is harmless but keeps
 * `size / scale` an exact integer for the GTK-side logical geometry.
 */
internal fun alignToBufferScale(
    px: Int,
    bufferScale: Int,
): Int {
    val scale = bufferScale.coerceAtLeast(1)
    if (px <= scale) return scale
    val remainder = px % scale
    return if (remainder == 0) px else px + (scale - remainder)
}
