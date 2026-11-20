package dev.nucleusframework.window.tao.popup

import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import java.util.concurrent.atomic.AtomicReference

/**
 * One popup layer's positioning decision, as pushed to the platform.
 *
 * Carries both sides of the #569 split — what Compose decided
 * ([boundsInWindowPx], window-rooted) and where the popup actually went
 * ([frameOnScreenPx], global screen physical pixels) — so a test can assert
 * not only that the popup is on screen but that the clamp is what put it
 * there.
 */
internal class PopupFrameRecord(
    /** `boundsInWindow` as Compose computed it, unclamped. Window-rooted physical px. */
    val boundsInWindowPx: IntRect,
    /**
     * The native surface's frame, in global screen physical px. Inflated past
     * [contentOnScreenPx] by whatever the popup draws outside its layout
     * bounds (shadows, the dialog appearance animation) — see
     * [PopupDrawInflate].
     */
    val frameOnScreenPx: IntRect,
    /** Where [boundsInWindowPx] landed, in global screen physical px: the popup as the user sees it. */
    val contentOnScreenPx: IntRect,
    /** [popupScreenClampOffset]'s verdict — [IntOffset.Zero] when nothing had to move. */
    val clampOffsetPx: IntOffset,
    /**
     * The layer's native popup handle: a `PopupState*` on Windows, an
     * `NSPanel*` on macOS, a [dev.nucleusframework.window.tao.TaoWindow] handle
     * on Linux. Opaque here; a platform-specific test dereferences it to read
     * the real on-screen rect back from the OS.
     */
    val panelHandle: Long,
)

/**
 * Last frame every native popup layer pushed — the seam the headful suite
 * asserts the #569 placement contract through ("a popup never lands outside
 * the work area of the display it belongs to").
 *
 * A native popup layer is not reachable from a test: Compose creates it inside
 * the scene's render pass, and it owns a `WS_POPUP` HWND / `NSPanel` /
 * override-redirect window nobody publishes. Recording the pushed frame at the
 * choke point is the smallest seam that makes the real placement observable —
 * and the *only* one that can tell an offscreen popup from a popup that just
 * happened to be anchored somewhere safe, since `boundsInWindow` is
 * deliberately left unclamped.
 *
 * Not reactive Compose state (unlike [dev.nucleusframework.window.tao.TaoDnDDiagnostics]):
 * these writes happen on the popup's frame path, where a snapshot write would
 * invalidate the very composition producing them.
 */
internal object TaoPopupDiagnostics {
    private val last = AtomicReference<PopupFrameRecord?>(null)

    /**
     * Most recently positioned popup layer. `null` until one pushes a real
     * frame; never cleared by the layers, so a test can read it after the
     * popup was dismissed.
     */
    val lastFrame: PopupFrameRecord? get() = last.get()

    /** Frames pushed since the last [reset], clamped or not. */
    @Volatile
    var frameCount: Int = 0
        private set

    fun record(record: PopupFrameRecord) {
        last.set(record)
        frameCount++
    }

    /**
     * Whether the most recently placed Linux popup layer let the *compositor*
     * position it (an `xdg_popup`, native Wayland) rather than placing itself.
     * `null` until one is placed. The Wayland half of the #569 contract: there
     * is no screen geometry to assert against there, so the placement decision
     * is what a test can hold on to.
     */
    @Volatile
    var lastCompositorPlaced: Boolean? = null

    /**
     * How many times the most recent run's compositor-placed layers anchored
     * (`xdg_positioner`). More than one means a popup was re-mapped because its
     * size changed after it was already on screen — the only way to keep the
     * `xdg_surface` geometry and the EGL buffer agreeing, since GDK positions a
     * popup once.
     */
    @Volatile
    var compositorAnchorCount: Int = 0

    fun reset() {
        last.set(null)
        frameCount = 0
        lastCompositorPlaced = null
        compositorAnchorCount = 0
    }
}
