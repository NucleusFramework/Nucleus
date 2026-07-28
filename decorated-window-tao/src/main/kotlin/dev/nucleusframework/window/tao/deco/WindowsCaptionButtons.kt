package dev.nucleusframework.window.tao.deco

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.positionInWindow
import dev.nucleusframework.window.tao.TaoWindow
import dev.nucleusframework.window.tao.ffi.NativeTaoBridge
import dev.nucleusframework.window.tao.ffi.NativeTaoWindowsDecoBridge
import dev.nucleusframework.window.tao.ffi.NativeTaoWindowsDecoBridge.CaptionButton
import java.util.EnumMap
import kotlin.math.roundToInt

/**
 * Registers the Compose-drawn minimize / maximize / close buttons with the
 * deco WndProc so `WM_NCHITTEST` reports them as real caption buttons.
 *
 * That single answer is what unlocks the native behaviour Windows attaches to
 * caption buttons — the system "Minimize"/"Maximize"/"Close" tooltips, and the
 * Windows 11 Snap Layouts flyout when hovering maximize. Nothing native is
 * drawn on top: `WM_NCCALCSIZE` leaves no non-client area for `DefWindowProc`
 * to paint into, so the buttons stay entirely Compose-rendered.
 *
 * The trade-off is that Windows then routes those rects through the
 * non-client message stream, so Compose receives no pointer events over them.
 * This holder carries the hover/press state and the click back from the
 * WndProc; all callbacks arrive on the Tao event-loop thread, which is the
 * Compose UI thread.
 */
@Stable
internal class WindowsCaptionButtons(
    private val hwnd: Long,
) {
    var hot by mutableStateOf<CaptionButton?>(null)
        private set

    var pressed by mutableStateOf<CaptionButton?>(null)
        private set

    private val actions = EnumMap<CaptionButton, () -> Unit>(CaptionButton::class.java)
    private val bounds = EnumMap<CaptionButton, IntArray>(CaptionButton::class.java)

    fun setAction(
        button: CaptionButton,
        action: () -> Unit,
    ) {
        actions[button] = action
    }

    /** Publishes the button rect (client physical px) to the WndProc. */
    fun reportBounds(
        button: CaptionButton,
        coordinates: LayoutCoordinates,
    ) {
        val position = coordinates.positionInWindow()
        val left = position.x.roundToInt()
        val top = position.y.roundToInt()
        val rect = intArrayOf(left, top, left + coordinates.size.width, top + coordinates.size.height)
        if (bounds[button].contentEquals(rect)) return
        bounds[button] = rect
        push(button, rect)
    }

    /** Drops the hit-test zone when the button leaves the composition. */
    fun release(button: CaptionButton) {
        actions -= button
        if (bounds.remove(button) != null) push(button, EMPTY_RECT)
    }

    internal fun onStateChanged(
        hot: CaptionButton?,
        pressed: CaptionButton?,
    ) {
        this.hot = hot
        this.pressed = pressed
    }

    internal fun onClick(button: CaptionButton) {
        actions[button]?.invoke()
    }

    internal fun releaseAll() {
        hot = null
        pressed = null
        actions.clear()
        bounds.keys.toList().forEach { push(it, EMPTY_RECT) }
        bounds.clear()
    }

    /** [rect] is `[left, top, right, bottom]` in client physical pixels. */
    private fun push(
        button: CaptionButton,
        rect: IntArray,
    ) {
        NativeTaoWindowsDecoBridge.nativeSetCaptionButtonBounds(
            hwnd,
            button.ordinal,
            rect[LEFT],
            rect[TOP],
            rect[RIGHT],
            rect[BOTTOM],
        )
    }

    private companion object {
        const val LEFT = 0
        const val TOP = 1
        const val RIGHT = 2
        const val BOTTOM = 3

        val EMPTY_RECT = intArrayOf(0, 0, 0, 0)
    }
}

/**
 * Per-window [WindowsCaptionButtons] holder, live for as long as the Windows
 * control area is composed. Returns `null` when the deco native library is
 * unavailable — the buttons then fall back to plain Compose hit-testing,
 * without the system tooltips or the Snap Layouts flyout.
 */
@Composable
internal fun rememberWindowsCaptionButtons(win: TaoWindow): WindowsCaptionButtons? {
    if (!NativeTaoWindowsDecoBridge.isLoaded) return null
    val hwnd = remember(win) { NativeTaoBridge.nativeHwndHandle(win.handle) }
    if (hwnd == 0L) return null

    val holder = remember(hwnd) { WindowsCaptionButtons(hwnd) }
    DisposableEffect(holder) {
        val listener =
            object : NativeTaoWindowsDecoBridge.CaptionButtonListener {
                override fun onStateChanged(
                    hot: CaptionButton?,
                    pressed: CaptionButton?,
                ) = holder.onStateChanged(hot, pressed)

                override fun onClick(button: CaptionButton) = holder.onClick(button)
            }
        NativeTaoWindowsDecoBridge.setCaptionButtonListener(hwnd, listener)
        onDispose {
            NativeTaoWindowsDecoBridge.setCaptionButtonListener(hwnd, null)
            holder.releaseAll()
        }
    }
    return holder
}
