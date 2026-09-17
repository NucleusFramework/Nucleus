package dev.nucleusframework.window.tao.deco

import androidx.compose.ui.geometry.Rect
import dev.nucleusframework.core.runtime.Platform
import dev.nucleusframework.window.WindowControlType
import dev.nucleusframework.window.tao.TaoWindow
import dev.nucleusframework.window.tao.ffi.NativeTaoBridge
import dev.nucleusframework.window.tao.ffi.NativeTaoWindowsDecoBridge

/**
 * Collects the window-space rects of the Compose-drawn caption buttons and
 * publishes them to the native decoration, where `WM_NCHITTEST` answers
 * `HTMINBUTTON`/`HTMAXBUTTON`/`HTCLOSE` over them — the Snap Layouts protocol:
 * that hit code is what makes Windows 11 show the snap flyout when the
 * maximize button is hovered. Interaction stays entirely with the Compose
 * buttons (the native side forwards the NC clicks back as client messages).
 *
 * Runs on the Tao main thread only (layout callbacks), so no synchronization
 * is needed.
 */
internal object CaptionButtonHitZones {
    private const val SLOT_COUNT = 3
    private const val INTS_PER_SLOT = 4

    /** Per-window slots, ordered minimize / maximize / close. */
    private val zones = HashMap<Long, Array<Rect?>>()

    /** Last array pushed per window, to keep layout-driven JNI traffic down. */
    private val pushed = HashMap<Long, IntArray>()

    private fun slotOf(type: WindowControlType): Int? =
        when (type) {
            WindowControlType.Minimize -> 0
            // Restore replaces Maximize on a maximized window and occupies the
            // same slot; the snap flyout still belongs on it.
            WindowControlType.Maximize, WindowControlType.Restore -> 1
            WindowControlType.Close -> 2
            // No snap flyout in fullscreen, and the button's action is not a
            // caption semantic Windows knows about.
            WindowControlType.ExitFullscreen -> null
        }

    fun publish(
        window: TaoWindow,
        type: WindowControlType,
        rect: Rect?,
    ) {
        if (Platform.Current != Platform.Windows || !NativeTaoWindowsDecoBridge.isLoaded) return
        val slot = slotOf(type) ?: return
        val slots = zones.getOrPut(window.handle) { arrayOfNulls(SLOT_COUNT) }
        slots[slot] = rect
        push(window, slots)
        if (slots.all { it == null }) {
            // Handles are native pointers and can be recycled by a later
            // window; drop empty entries rather than keeping them around.
            zones.remove(window.handle)
            pushed.remove(window.handle)
        }
    }

    private fun push(
        window: TaoWindow,
        slots: Array<Rect?>,
    ) {
        val rects = IntArray(SLOT_COUNT * INTS_PER_SLOT)
        slots.forEachIndexed { i, rect ->
            if (rect != null && !rect.isEmpty) {
                var at = i * INTS_PER_SLOT
                rects[at++] = rect.left.toInt()
                rects[at++] = rect.top.toInt()
                rects[at++] = rect.width.toInt()
                rects[at] = rect.height.toInt()
            }
        }
        if (pushed[window.handle]?.contentEquals(rects) == true) return
        val hwnd = NativeTaoBridge.nativeHwndHandle(window.handle)
        if (hwnd == 0L) return
        NativeTaoWindowsDecoBridge.nativeSetCaptionButtonRects(hwnd, rects)
        pushed[window.handle] = rects
    }
}
