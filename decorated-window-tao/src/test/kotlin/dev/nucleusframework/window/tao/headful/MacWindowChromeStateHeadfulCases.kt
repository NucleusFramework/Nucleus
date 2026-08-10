package dev.nucleusframework.window.tao.headful

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import dev.nucleusframework.window.tao.NativeView
import dev.nucleusframework.window.tao.NucleusPlatformView
import dev.nucleusframework.window.tao.ffi.NativeMetalBridge
import dev.nucleusframework.window.tao.ffi.NativeTaoBridge
import dev.nucleusframework.window.tao.ffi.NativeTaoMacOsNativeViewBridge
import dev.nucleusframework.window.tao.scene.LocalTaoMetalTextureHost
import dev.nucleusframework.window.tao.scene.TaoMetalTextureHost
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Headful e2e cases for the macOS window-level native state reported against
 * the #494 follow-up patch: real Tao window, real AppKit objects, live
 * assertions through the [NativeMetalBridge] diagnostics.
 */
internal object MacWindowChromeStateHeadfulCases {
    private val isMac: Boolean =
        System.getProperty("os.name", "").lowercase().let {
            it.contains("mac") || it.contains("darwin")
        }

    fun all(): List<TaoWindowTestCase> =
        listOf(
            overlayDetachKeepsWindowChromeState(),
            setFocusableDoesNotLeakWindowRetains(),
            fullscreenWithLiveNativeViewDoesNotFreeze(),
            nativeViewTracksFullscreenRoundTrip(),
            renderThreadHopStaysCheap(),
        )

    /** Bit 0 = primary attachment associated object, bit 1 = FS observer. */
    private const val WINDOW_STATE_INTACT = 3

    /**
     * Detaching an overlay Metal attachment must not tear down the host
     * window's own native state. The driver replays the exact JNI sequence
     * `NativeViewOverlayController` runs when a `NativeView` is composed and
     * disposed (`nativeCreateOverlay` → `nativeAttachOverlay`, then
     * `nativeDetach` → `nativeReleaseOverlay`, all on the main thread) — an
     * unguarded detach wipes the window's primary-attachment associated
     * object and the fullscreen observer (the #327 machinery), killing
     * fullscreen transitions and `attachmentForWindow`-based features for
     * the rest of the window's life.
     *
     * Driven at the bridge level rather than through the `NativeView`
     * composable: the composable path can deadlock a mid-flight
     * `nativePresentWithInterop` (render thread `dispatch_sync` to main vs.
     * main blocked in `runOnRenderThread(...).get()` creating the overlay's
     * DirectContext) — a separate pre-existing race, not what this case
     * guards.
     */
    private fun overlayDetachKeepsWindowChromeState(): TaoWindowTestCase =
        TaoWindowTestCase(
            name = "overlay detach keeps main-window chrome state (#494 patch)",
            skip = { if (!isMac) "macOS only" else null },
        ) {
            awaitUntil("window mapped") { bounds() != null }
            settle()
            val contentNsView = NativeTaoBridge.nativeNsViewHandle(window.handle)
            check(contentNsView != 0L) { "content NSView must be non-zero on a mapped window" }

            val before = NativeMetalBridge.nativeDiagWindowState(contentNsView)
            check(before == WINDOW_STATE_INTACT) {
                "window state must be intact before the overlay round-trip (diag=$before)"
            }

            // NativeViewOverlayController.attach()'s native half.
            val overlayNsView = NativeTaoMacOsNativeViewBridge.nativeCreateOverlay(contentNsView)
            check(overlayNsView != 0L) { "nativeCreateOverlay failed" }
            val attachment = NativeMetalBridge.nativeAttachOverlay(overlayNsView)
            check(attachment != 0L) { "nativeAttachOverlay failed" }
            settle()

            // NativeViewOverlayController.dispose()'s native half, same order.
            NativeMetalBridge.nativeDetach(attachment)
            NativeTaoMacOsNativeViewBridge.nativeReleaseOverlay(overlayNsView)
            settle()

            val after = NativeMetalBridge.nativeDiagWindowState(contentNsView)
            check(after == WINDOW_STATE_INTACT) {
                "overlay nativeDetach wiped main-window native state: diag=$after " +
                    "(bit0=primary attachment, bit1=fullscreen observer)"
            }
        }

    /**
     * `TaoWindow.setFocusable` funnels into the vendored tao's
     * `set_focusable`, which used to retain the NSWindow (+1 per call via
     * `clone()` + `Retained::into_raw`) and never release it — the window
     * could never deallocate after close. Asserted via the retain-count
     * delta across a burst of calls.
     */
    private fun setFocusableDoesNotLeakWindowRetains(): TaoWindowTestCase =
        TaoWindowTestCase(
            name = "setFocusable does not leak NSWindow retains (#494 patch)",
            skip = { if (!isMac) "macOS only" else null },
        ) {
            awaitUntil("window mapped") { bounds() != null }
            settle()
            val nsView = window.nativeHandle
            check(nsView != 0L) { "nativeHandle must be non-zero on a mapped window" }

            val before = NativeMetalBridge.nativeDiagWindowRetainCount(nsView)
            check(before > 0) { "retain-count diagnostic unavailable (got $before)" }

            // `true` keeps behavior unchanged (the window is already
            // focusable); the leak was in the pointer plumbing, not the value.
            repeat(FOCUSABLE_CALLS) { window.setFocusable(true) }
            // setFocusable is queued through the tao user-event loop — let it
            // drain fully before measuring.
            settle()

            val after = NativeMetalBridge.nativeDiagWindowRetainCount(nsView)
            check(after > 0) { "retain-count diagnostic unavailable after calls (got $after)" }
            val delta = after - before
            check(delta < FOCUSABLE_CALLS / 2) {
                "set_focusable leaked ~$delta NSWindow retains over $FOCUSABLE_CALLS calls " +
                    "(before=$before after=$after) — Retained::into_raw without a matching release"
            }
        }

    private const val FOCUSABLE_CALLS = 40

    /**
     * Entering fullscreen with a live `NativeView` must not deadlock. The
     * freeze: `windowWillEnterFullScreen` → `prepareFullscreenFrame` →
     * `renderFrameBlocking` parks the main thread on the render executor,
     * while the render thread sits inside `nativePresentWithInterop`'s
     * main-thread callout (present-with-transaction) — AB-BA. Fixed by
     * scheduling the callout in a private run-loop mode that the blocked main
     * thread pumps ([NativeMetalBridge.nativeInteropPump]).
     *
     * The content keeps an animation running and animates the embed's
     * position so every frame carries an interop action — the exact regime
     * the deadlock needs (an interop present in flight when fullscreen
     * starts).
     */
    private fun fullscreenWithLiveNativeViewDoesNotFreeze(): TaoWindowTestCase {
        var showNativeView by mutableStateOf(false)
        val childViewPtr = AtomicLong(0)
        val embeddingComposed = AtomicLong(0)
        return TaoWindowTestCase(
            name = "fullscreen with a live NativeView does not deadlock (#494 patch)",
            timeoutMillis = 90_000L,
            skip = { if (!isMac) "macOS only" else null },
            content = {
                // Captured before Box: the @LayoutScopeMarker on BoxScope
                // blocks the outer TaoDecoratedWindowScope implicit receiver.
                val taoWindow = window
                val drive = rememberInfiniteTransition(label = "drive")
                val phase by drive.animateFloat(
                    initialValue = 0f,
                    targetValue = 1f,
                    animationSpec = infiniteRepeatable(tween(PHASE_PERIOD_MS, easing = LinearEasing)),
                    label = "phase",
                )
                Box(Modifier.fillMaxSize().background(Color.DarkGray)) {
                    Box(
                        Modifier
                            .size(80.dp)
                            .graphicsLayer { rotationZ = phase * FULL_TURN_DEGREES }
                            .background(Color(0xFF3366CC)),
                    )
                    if (showNativeView) {
                        val parentNsView = NativeTaoBridge.nativeNsViewHandle(taoWindow.handle)
                        val child =
                            remember(parentNsView) {
                                if (parentNsView != 0L) {
                                    NativeTaoMacOsNativeViewBridge
                                        .nativeCreateOverlay(parentNsView)
                                        .also(childViewPtr::set)
                                } else {
                                    0L
                                }
                            }
                        if (child != 0L) {
                            SideEffect { embeddingComposed.set(1) }
                            NativeView(
                                factory = {
                                    object : NucleusPlatformView.NsView {
                                        override val nsViewHandle: Long = child
                                    }
                                },
                                modifier =
                                    Modifier
                                        .offset {
                                            IntOffset(
                                                EMBED_BASE_OFFSET_PX + (phase * EMBED_SWAY_PX).roundToInt(),
                                                EMBED_BASE_OFFSET_PX,
                                            )
                                        }.size(120.dp),
                            )
                        }
                    }
                }
            },
        ) {
            awaitUntil("window mapped") { bounds() != null }
            settle()
            val before = requireNotNull(bounds())

            // Mount the NativeView during steady-state rendering (the demo
            // navigation pattern), then let interop presents get going.
            showNativeView = true
            awaitUntil("NativeView embedding composed") { embeddingComposed.get() == 1L }
            settle(INTEROP_WARMUP_MS)

            val fsRequestedAt = System.currentTimeMillis()
            window.setFullscreen(true)
            awaitUntil("entered fullscreen (bounds grew)", timeoutMillis = FS_TIMEOUT_MS) {
                val b = bounds() ?: return@awaitUntil false
                b[2] > before[2] + FS_GROWTH_MIN_PX
            }
            // Latency guard: a deadlock resolved only by the 2s present
            // backstop (instead of the cooperative interop pump) still enters
            // fullscreen, just ~2-3s late. The nominal entry is well under a
            // second; anything past the backstop means the pump didn't run.
            val fsEntryMs = System.currentTimeMillis() - fsRequestedAt
            check(fsEntryMs < FS_ENTRY_MAX_MS) {
                "fullscreen entry stalled for ${fsEntryMs}ms — the interop present " +
                    "rode the 2s backstop instead of being pumped cooperatively"
            }
            // Keep animating in fullscreen for a moment — interop presents must
            // keep flowing there too.
            settle(INTEROP_WARMUP_MS)

            window.setFullscreen(false)
            awaitUntil("exited fullscreen (bounds restored)", timeoutMillis = FS_TIMEOUT_MS) {
                val b = bounds() ?: return@awaitUntil false
                abs(b[2] - before[2]) <= FS_RESTORE_TOLERANCE_PX
            }
            settle()

            // The window survived a full interop-active fullscreen round-trip
            // and its native chrome state is still intact.
            val nsView = NativeTaoBridge.nativeNsViewHandle(window.handle)
            val diag = NativeMetalBridge.nativeDiagWindowState(nsView)
            check(diag == WINDOW_STATE_INTACT) {
                "window chrome state lost across the fullscreen round-trip (diag=$diag)"
            }

            showNativeView = false
            settle()
            childViewPtr.get().takeIf { it != 0L }?.let {
                NativeTaoMacOsNativeViewBridge.nativeReleaseOverlay(it)
            }
        }
    }

    /**
     * The embedded NSView must track its Compose slot's size through a
     * fullscreen round-trip: a `NativeView(Modifier.fillMaxSize())` has to
     * grow to the fullscreen layout on enter and shrink back on exit —
     * asserted against the AppKit view's REAL frame, not Compose state.
     */
    private fun nativeViewTracksFullscreenRoundTrip(): TaoWindowTestCase {
        val childViewPtr = AtomicLong(0)
        val embeddingComposed = AtomicLong(0)
        // Laid-out width of the overlay `content` slot, in px, packed by the
        // inner scene's own layout pass — the only signal of the overlay
        // ComposeScene's real viewport.
        val overlayContentWidthPx = AtomicLong(0)
        val expectedRect = AtomicLong(0)
        return TaoWindowTestCase(
            name = "NativeView frame tracks a fullscreen round-trip (#494 patch)",
            timeoutMillis = 90_000L,
            skip = { if (!isMac) "macOS only" else null },
            // The suite's default DarkGray Box(fillMaxSize) is a Column
            // sibling composed BEFORE the case content — it would eat the
            // whole height and leave the NativeView a degenerate 1px slot.
            paintDefaultBackground = false,
            content = {
                FillingNativeViewProbe(
                    window.handle,
                    childViewPtr,
                    embeddingComposed,
                    overlayContentWidthPx,
                    expectedRect,
                )
            },
        ) {
            awaitUntil("window mapped") { bounds() != null }
            settle()
            awaitUntil("NativeView embedding composed") { embeddingComposed.get() == 1L }
            settle()

            fun childSizePx(): Pair<Long, Long> {
                val packed = NativeMetalBridge.nativeDiagViewFrameSize(childViewPtr.get())
                return (packed ushr 32) to (packed and 0xFFFFFFFFL)
            }

            fun childTopLeftPx(): Pair<Int, Int> {
                val packed = NativeMetalBridge.nativeDiagViewTopLeftPx(childViewPtr.get())
                check(packed != Long.MIN_VALUE) { "embedded NSView left the hierarchy" }
                return (packed ushr 32).toInt() to packed.toInt()
            }

            // The contract: the embed's REAL AppKit frame (top-left origin)
            // must match the Compose slot rect, whatever the window state.
            // Correct SIZE at the wrong offset is the stale-anchor failure
            // mode a size-only check can't see.
            fun checkChildMatchesSlot(stage: String) {
                val (x, y) = childTopLeftPx()
                val (w, h) = childSizePx()
                val exp = unpackRect(expectedRect.get())
                System.err.println(
                    "[nv-diag] $stage child=($x,$y ${w}x$h) slot=(${exp[0]},${exp[1]} ${exp[2]}x${exp[3]})",
                )
                check(exp[2] > 1 && exp[3] > 1) {
                    "probe slot is degenerate (${exp[2]}x${exp[3]}) — test layout broken"
                }
                check(
                    abs(x - exp[0]) <= TRACK_TOLERANCE_PX &&
                        abs(y - exp[1]) <= TRACK_TOLERANCE_PX &&
                        abs(w - exp[2]) <= TRACK_TOLERANCE_PX &&
                        abs(h - exp[3]) <= TRACK_TOLERANCE_PX,
                ) {
                    "embedded NSView diverged from its Compose slot $stage: " +
                        "view=($x,$y ${w}x$h) slot=(${exp[0]},${exp[1]} ${exp[2]}x${exp[3]})"
                }
            }

            val windowedBounds = requireNotNull(bounds())
            val (w0, h0) = childSizePx()
            check(w0 > 0 && h0 > 0) { "embedded NSView never got an initial frame (${w0}x$h0)" }

            window.setFullscreen(true)
            awaitUntil("entered fullscreen (bounds grew)", timeoutMillis = FS_TIMEOUT_MS) {
                val b = bounds() ?: return@awaitUntil false
                b[2] > windowedBounds[2] + FS_GROWTH_MIN_PX
            }
            settle(FS_SETTLE_MS)
            val fsBounds = requireNotNull(bounds())
            val (wFs, hFs) = childSizePx()
            // fillMaxSize minus chrome: width must match the window's client
            // width; height is within the title-bar inset.
            check(abs(wFs - fsBounds[2]) <= TRACK_TOLERANCE_PX) {
                "embedded NSView did not grow to the fullscreen layout: " +
                    "view=${wFs}x$hFs window=${fsBounds[2]}x${fsBounds[3]} (was ${w0}x$h0)"
            }
            // The overlay `content` slot must have relaid out to the same
            // width — a stale inner-scene viewport keeps the Compose content
            // (and its hit regions) at the pre-fullscreen size.
            val overlayFsWidth = overlayContentWidthPx.get()
            check(abs(overlayFsWidth - wFs) <= TRACK_TOLERANCE_PX) {
                "NativeView overlay content kept a stale viewport in fullscreen: " +
                    "content=${overlayFsWidth}px embed=${wFs}px"
            }
            checkChildMatchesSlot("in fullscreen")

            window.setFullscreen(false)
            awaitUntil("exited fullscreen (bounds restored)", timeoutMillis = FS_TIMEOUT_MS) {
                val b = bounds() ?: return@awaitUntil false
                abs(b[2] - windowedBounds[2]) <= FS_RESTORE_TOLERANCE_PX
            }
            settle(FS_SETTLE_MS)
            val restoredBounds = requireNotNull(bounds())
            val (wBack, hBack) = childSizePx()
            check(abs(wBack - restoredBounds[2]) <= TRACK_TOLERANCE_PX) {
                "embedded NSView kept stale dimensions after exiting fullscreen: " +
                    "view=${wBack}x$hBack window=${restoredBounds[2]}x${restoredBounds[3]} " +
                    "(fullscreen was ${wFs}x$hFs)"
            }
            checkChildMatchesSlot("after exiting fullscreen")

            // A window resize AFTER the round-trip must still reach the
            // embed — catches a wedged interop pipeline that the restore
            // path alone can miss (the restore size is re-applied by the
            // fullscreen-prepare machinery, a resize is not).
            val scale = window.scaleFactor.coerceAtLeast(1f).toDouble()
            window.setInnerSize(
                restoredBounds[2] / scale + POST_FS_RESIZE_DELTA_DP,
                restoredBounds[3] / scale + POST_FS_RESIZE_DELTA_DP,
            )
            awaitUntil("window resized after the round-trip", timeoutMillis = FS_TIMEOUT_MS) {
                val b = bounds() ?: return@awaitUntil false
                b[2] > restoredBounds[2] + (POST_FS_RESIZE_DELTA_DP * scale / 2).toInt()
            }
            settle(FS_SETTLE_MS)
            val resizedBounds = requireNotNull(bounds())
            val (wResized, _) = childSizePx()
            check(abs(wResized - resizedBounds[2]) <= TRACK_TOLERANCE_PX) {
                "embedded NSView stopped tracking resizes after the fullscreen " +
                    "round-trip: view width=$wResized window width=${resizedBounds[2]}"
            }
            val overlayResizedWidth = overlayContentWidthPx.get()
            check(abs(overlayResizedWidth - wResized) <= TRACK_TOLERANCE_PX) {
                "NativeView overlay content kept a stale viewport after the " +
                    "round-trip + resize: content=${overlayResizedWidth}px embed=${wResized}px"
            }
            checkChildMatchesSlot("after the round-trip + resize")

            childViewPtr.get().takeIf { it != 0L }?.let {
                NativeTaoMacOsNativeViewBridge.nativeReleaseOverlay(it)
            }
        }
    }

    /**
     * A `NativeView(Modifier.fillMaxSize())` around a fabricated NSView, with
     * a content slot that reports its laid-out width — the probe body of
     * [nativeViewTracksFullscreenRoundTrip].
     */
    @androidx.compose.runtime.Composable
    private fun FillingNativeViewProbe(
        windowHandle: Long,
        childViewPtr: AtomicLong,
        embeddingComposed: AtomicLong,
        overlayContentWidthPx: AtomicLong,
        /** Compose-side slot rect, packed x/y/w/h × 16 bits — the contract the native frame must match. */
        expectedRect: AtomicLong,
    ) {
        val parentNsView = NativeTaoBridge.nativeNsViewHandle(windowHandle)
        val child =
            remember(parentNsView) {
                if (parentNsView != 0L) {
                    NativeTaoMacOsNativeViewBridge
                        .nativeCreateOverlay(parentNsView)
                        .also(childViewPtr::set)
                } else {
                    0L
                }
            }
        if (child != 0L) {
            SideEffect { embeddingComposed.set(1) }
            Box(
                Modifier.fillMaxSize().onGloballyPositioned { c ->
                    val p = c.positionInRoot()
                    expectedRect.set(
                        packRect(p.x.roundToInt(), p.y.roundToInt(), c.size.width, c.size.height),
                    )
                },
            ) {
                NativeView(
                    factory = {
                        object : NucleusPlatformView.NsView {
                            override val nsViewHandle: Long = child
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                    content = {
                        Box(
                            Modifier.fillMaxSize().onGloballyPositioned {
                                overlayContentWidthPx.set(it.size.width.toLong())
                            },
                        )
                    },
                )
            }
        }
    }

    private fun packRect(
        x: Int,
        y: Int,
        w: Int,
        h: Int,
    ): Long =
        ((x.toLong() and 0xFFFF) shl 48) or
            ((y.toLong() and 0xFFFF) shl 32) or
            ((w.toLong() and 0xFFFF) shl 16) or
            (h.toLong() and 0xFFFF)

    private fun unpackRect(packed: Long): IntArray =
        intArrayOf(
            ((packed ushr 48) and 0xFFFF).toInt(),
            ((packed ushr 32) and 0xFFFF).toInt(),
            ((packed ushr 16) and 0xFFFF).toInt(),
            (packed and 0xFFFF).toInt(),
        )

    /**
     * A main-thread `runOnRenderThread` hop must stay in the sub-millisecond
     * range: `TextureView` performs one blocking hop per producer frame from
     * the draw pass (`MacImportedTexture.snapshot`), so any fixed cost added
     * to the hop is paid per video frame. The interop pump's first version
     * parked the main thread for the pump timeout (4ms) when no callout was
     * pending — +4ms per frame halves 90Hz video to 45fps.
     */
    private fun renderThreadHopStaysCheap(): TaoWindowTestCase {
        val hostRef = AtomicReference<TaoMetalTextureHost?>()
        return TaoWindowTestCase(
            name = "main-thread render hop stays sub-millisecond (#494 patch)",
            skip = { if (!isMac) "macOS only" else null },
            content = {
                val host = LocalTaoMetalTextureHost.current
                SideEffect { hostRef.set(host) }
            },
        ) {
            awaitUntil("window mapped") { bounds() != null }
            settle()
            awaitUntil("texture host published") { hostRef.get() != null }
            val host = requireNotNull(hostRef.get())

            repeat(HOP_WARMUP) { host.runOnRenderThread { } }
            val start = System.nanoTime()
            repeat(HOP_SAMPLES) { host.runOnRenderThread { } }
            val avgMicros = (System.nanoTime() - start) / 1_000 / HOP_SAMPLES
            System.err.println("[hop-diag] avg main->render hop = ${avgMicros}µs over $HOP_SAMPLES hops")
            check(avgMicros < HOP_MAX_MICROS) {
                "main-thread render hop costs ${avgMicros}µs on average — a fixed " +
                    "stall here is paid once per TextureView producer frame " +
                    "(4000µs ≈ the interop pump parking for its full timeout)"
            }
        }
    }

    private const val HOP_WARMUP = 20
    private const val HOP_SAMPLES = 200
    private const val HOP_MAX_MICROS = 1_000L

    private const val PHASE_PERIOD_MS = 500
    private const val FULL_TURN_DEGREES = 360f
    private const val EMBED_BASE_OFFSET_PX = 140
    private const val EMBED_SWAY_PX = 60f
    private const val INTEROP_WARMUP_MS = 1_500L
    private const val FS_TIMEOUT_MS = 20_000L
    private const val FS_ENTRY_MAX_MS = 2_000L
    private const val FS_GROWTH_MIN_PX = 200
    private const val FS_RESTORE_TOLERANCE_PX = 64
    private const val FS_SETTLE_MS = 1_200L
    private const val TRACK_TOLERANCE_PX = 8
    private const val POST_FS_RESIZE_DELTA_DP = 120.0
}
