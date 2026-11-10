package dev.nucleusframework.window.tao.headful

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import dev.nucleusframework.core.runtime.Platform
import dev.nucleusframework.window.tao.NativeView
import dev.nucleusframework.window.tao.ffi.NativeTaoBridge
import dev.nucleusframework.window.tao.ffi.NativeTaoMacOsNativeViewBridge
import dev.nucleusframework.window.tao.nucleusNsPlatformView
import java.util.concurrent.atomic.AtomicLong

/**
 * A `NativeView` that moves on every frame must be, once a frame is on screen,
 * where that frame laid it out — not where the frame before did. The AppKit
 * mutations a frame's layout queues have to ride on that frame's present.
 *
 * Checked from the layout itself: when a frame places the view, the frame
 * before it has been presented, so AppKit must hold that frame's position.
 */
internal object NativeViewFrameSyncHeadfulCases {
    fun all(): List<TaoWindowTestCase> = listOf(movesWithItsFrame(overlay = false), movesWithItsFrame(overlay = true))

    private fun movesWithItsFrame(overlay: Boolean): TaoWindowTestCase {
        val view = MovingView(overlay)
        val placement = if (overlay) "placed as overlay" else "under the surface"
        return TaoWindowTestCase(
            name = "a NativeView $placement moves with the frame that laid it out",
            skip = ::macOnly,
            // The default background is a sibling that fills the window and pushes the content out of it.
            paintDefaultBackground = false,
            content = { view.Content() },
        ) {
            view.content = NativeTaoBridge.nativeNsViewHandle(window.handle)
            awaitUntil("the view is mounted") { view.nativeX() != null }
            settle()
            view.calibrate()
            view.moving.value = true
            awaitUntil("the last step is laid out") { view.placed == FRAMES * STEP_PX }
            check(view.samples >= FRAMES / 2) { "only ${view.samples} of $FRAMES steps were laid out one by one" }
            check(view.late == 0) {
                "${view.late} of ${view.samples} frames found the view behind the frame before them: ${view.firstLate}"
            }
        }
    }

    private class MovingView(
        private val overlay: Boolean,
    ) {
        val field = AtomicLong(0)

        /** The window's content NSView, which an overlay's rect is read against. */
        var content: Long = 0L
        val shift = mutableIntStateOf(0)

        /** Set by the case once the view is at rest: one step on every frame from then on. */
        val moving = mutableStateOf(false)

        /** The shift the last frame placed the view at. */
        var placed: Int = 0
            private set
        var samples = 0
            private set
        var late = 0
            private set
        var firstLate: String? = null
            private set

        /** AppKit's x for a shift of 0, read at rest. */
        private var origin: Int? = null

        fun nativeX(): Int? {
            val handle = field.get().takeIf { it != 0L } ?: return null
            val frame =
                if (overlay) {
                    NativeTaoMacOsNativeViewBridge.nativeDiagOverlayClip(content, handle)
                } else {
                    NativeTaoMacOsNativeViewBridge.nativeDiagViewFrame(handle)
                }
            return frame?.get(0)
        }

        fun calibrate() {
            origin = checkNotNull(nativeX()) - placed
        }

        /** Runs while a frame places the view [next] px along. */
        private fun onPlace(next: Int) {
            val origin = origin
            if (origin != null && next != placed) {
                samples++
                val native = nativeX()?.minus(origin)
                if (native != placed) {
                    late++
                    if (firstLate == null) firstLate = "AppKit had it at $native px, the frame before at $placed px"
                }
            }
            placed = next
        }

        @Composable
        fun Content() {
            val handle =
                remember {
                    NativeTaoMacOsNativeViewBridge.nativeDiagCreateTextField().also(field::set)
                }
            if (moving.value) {
                LaunchedEffect(Unit) {
                    repeat(FRAMES) { withFrameNanos { shift.intValue += STEP_PX } }
                }
            }
            Box(Modifier.fillMaxSize()) {
                NativeView(
                    factory = {
                        nucleusNsPlatformView(
                            handle = { handle },
                            onDispose = { NativeTaoMacOsNativeViewBridge.nativeDiagReleaseView(handle) },
                        )
                    },
                    modifier =
                        Modifier.layout { measurable, _ ->
                            val placeable = measurable.measure(Constraints.fixed(VIEW_W, VIEW_H))
                            layout(VIEW_W, VIEW_H) {
                                val next = shift.intValue
                                onPlace(next)
                                placeable.place(VIEW_X + next, VIEW_Y)
                            }
                        },
                    placedAsOverlay = overlay,
                )
            }
        }
    }

    private fun macOnly(): String? = if (Platform.Current != Platform.MacOS) "reads AppKit's frames" else null

    // Even numbers: whole points on a 2x display, so AppKit's frames read back exactly.
    private const val VIEW_X = 40
    private const val VIEW_Y = 160
    private const val VIEW_W = 240
    private const val VIEW_H = 80
    private const val STEP_PX = 8
    private const val FRAMES = 60
}
