package dev.nucleusframework.window.tao.headful

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowState
import dev.nucleusframework.core.runtime.Platform
import dev.nucleusframework.window.tao.scene.TaoPresentDiagnostics
import java.util.concurrent.atomic.AtomicReference

/**
 * #817 — raising `minimumSize` in the same frame as a [WindowState.size] grow
 * must still present a frame at the final size. The content is static, so a
 * frame left at an intermediate size is never replaced: Core Animation shows
 * the old drawable in a corner of the larger window for good.
 */
internal object MinimumSizeResizeHeadfulCases {
    fun all(): List<TaoWindowTestCase> = listOf(growWithMinimumSizePresentsFinalSize())

    private fun growWithMinimumSizePresentsFinalSize(): TaoWindowTestCase {
        val state =
            WindowState(
                position = WindowPosition.Absolute(40.dp, 60.dp),
                size = DpSize(640.dp, 480.dp),
            )
        val minimumSize = mutableStateOf<DpSize?>(DpSize(600.dp, 400.dp))
        val resized = AtomicReference<IntSize?>(null)
        return TaoWindowTestCase(
            name = "#817 growing with a raised minimumSize presents the final size",
            // Same oracle as #576's PresentLagProbe: only the Metal and ANGLE hosts record presents.
            skip = { "the Linux host records no presents".takeIf { Platform.Current == Platform.Linux } },
            windowState = state,
            size = DpSize(640.dp, 480.dp),
            minimumSize = minimumSize,
        ) {
            window.onResized { w, h -> resized.set(IntSize(w, h)) }
            awaitUntil("window mapped") { window.hasRealFramePx() }
            settle()
            // Kept within a 1024x768 screen (the macOS CI runner): AppKit clamps a taller
            // window to the visible frame, and the grown size would never be reached.
            Snapshot.withMutableSnapshot {
                minimumSize.value = DpSize(760.dp, 520.dp)
                state.size = DpSize(840.dp, 560.dp)
            }
            val scale = window.scaleFactor
            val target = IntSize((840 * scale).toInt(), (560 * scale).toInt())
            awaitUntil("window grown", detail = { "resized=${resized.get()}" }) { resized.get() == target }
            // Nothing in the content changes after the resize: whatever is presented now stays.
            settle(SETTLE_MILLIS)
            val presented = TaoPresentDiagnostics.lastPresentedPx(window.handle)
            System.err.println("[#817] window=${resized.get()} presented=$presented")
            check(presented == target) {
                "last presented frame is $presented while the window is ${resized.get()} — " +
                    "the static content is left drawn at a stale size"
            }
        }
    }

    private const val SETTLE_MILLIS = 1_000L
}
