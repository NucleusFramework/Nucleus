package dev.nucleusframework.window.tao.headful

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.nucleusframework.core.runtime.Platform
import dev.nucleusframework.window.tao.NativeView
import dev.nucleusframework.window.tao.NucleusPlatformView
import dev.nucleusframework.window.tao.TextureView
import dev.nucleusframework.window.tao.nucleusGtkPlatformView
import dev.nucleusframework.window.tao.nucleusHwndPlatformView
import dev.nucleusframework.window.tao.nucleusNsPlatformView
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs

/**
 * Windows pushed to the shapes an application only reaches by accident: fully
 * transparent, one pixel across, resized faster than the compositor can
 * answer, and carrying an embedded native view or an external texture while it
 * all happens.
 *
 * These are the conditions every layer disagrees about. The scene has a size,
 * the native window has another, the platform reports a third for a frame; an
 * embedded child is placed in physical pixels against a rect that may already
 * be stale; a texture is imported for a surface that is about to be destroyed.
 * The invariants asserted here are the ones that hold whatever the sizes are:
 *
 *  1. the scene ends up agreeing with the window, however many sizes were
 *     asked for in between;
 *  2. the render loop is still ticking afterwards — a window that survives a
 *     resize storm but stops painting is not a survivor;
 *  3. an embedded native view is never handed a rect the platform would refuse
 *     (negative, or outside the window), and is placed where the composable
 *     ended up;
 *  4. nothing above leaks when the content is added and removed over and over.
 */
internal object WindowExtremesHeadfulCases {
    fun all(): List<TaoWindowTestCase> =
        listOf(
            aResizeStormEndsWithTheSceneMatchingTheWindow(),
            aResizeStormLeavesTheRenderLoopTicking(),
            aWindowSqueezedToOnePixelComesBack(),
            aTinyWindowStillLaysOutAndGrowsBack(),
            aTransparentWindowSurvivesAResizeStorm(),
            aTransparentWindowSqueezedToNothingKeepsPainting(),
            anAnimationKeepsRunningThroughAResizeStorm(),
            alternatingSizesNeverLeaveTheSceneBehind(),
            aNativeViewIsPlacedWhereItsComposableEndedUp(),
            aNativeViewNeverGetsANegativeRect(),
            aNativeViewAddedAndRemovedRepeatedlyIsBalanced(),
            aNativeViewSurvivesAResizeStormAndKeepsItsRect(),
            aNativeViewInATransparentWindowIsStillPlaced(),
            aTextureViewWithoutASourceIsHarmless(),
            aTextureViewAppearingAndDisappearingDuringAStorm(),
            aTextureViewSignalledFasterThanTheLoopDoesNotStarveIt(),
            aTabStripInAWindowTooSmallForItStaysConsistent(),
            aSatelliteKeepsItsOffsetThroughAResizeStorm(),
        )

    // ── 1. resize storms ─────────────────────────────────────────────────

    /**
     * Sizes asked for faster than the platform answers. Only the last one
     * matters, and what must hold at the end is that the scene Compose lays
     * out in is the size the window really has — a scene left behind means
     * content drawn for a window that is not there any more.
     */
    private fun aResizeStormEndsWithTheSceneMatchingTheWindow(): TaoWindowTestCase {
        val probe = ExtremeProbe()
        return TaoWindowTestCase(
            name = "window extremes a resize storm ends with the scene matching the window",
            timeoutMillis = LONG_CASE_TIMEOUT_MILLIS,
            size = DpSize(START_W_DP.dp, START_H_DP.dp),
            paintDefaultBackground = false,
            content = { probe.Content(window.nativeHandle) },
            driver = {
                awaitProbe(probe)
                stormResize(window, ROUNDS)
                window.setInnerSize(END_W_DP, END_H_DP)
                awaitSettledAt(probe, window, END_W_DP, END_H_DP)
            },
        )
    }

    /** A window that survives a resize storm but stops painting has not survived it. */
    private fun aResizeStormLeavesTheRenderLoopTicking(): TaoWindowTestCase {
        val probe = ExtremeProbe(animate = true)
        return TaoWindowTestCase(
            name = "window extremes a resize storm leaves the render loop ticking",
            timeoutMillis = LONG_CASE_TIMEOUT_MILLIS,
            size = DpSize(START_W_DP.dp, START_H_DP.dp),
            paintDefaultBackground = false,
            content = { probe.Content(window.nativeHandle) },
            driver = {
                awaitProbe(probe)
                awaitUntil("the loop is ticking to begin with") { probe.frames.get() > MIN_FRAMES }
                stormResize(window, ROUNDS)
                window.setInnerSize(END_W_DP, END_H_DP)
                awaitSettledAt(probe, window, END_W_DP, END_H_DP)

                val before = probe.frames.get()
                settle(FRAME_WINDOW_MILLIS)
                val after = probe.frames.get()
                check(after - before >= MIN_FRAMES) {
                    "only ${after - before} frames in ${FRAME_WINDOW_MILLIS}ms after the storm"
                }
            },
        )
    }

    /**
     * One pixel across. Every layer has a lower bound it clamps to — the WM's,
     * GTK's, the swapchain's — and the interesting part is coming back: a
     * surface destroyed at 1×1 has to be rebuilt at the size that follows.
     */
    private fun aWindowSqueezedToOnePixelComesBack(): TaoWindowTestCase {
        val probe = ExtremeProbe(animate = true)
        return TaoWindowTestCase(
            name = "window extremes a window squeezed to one pixel comes back",
            timeoutMillis = LONG_CASE_TIMEOUT_MILLIS,
            size = DpSize(START_W_DP.dp, START_H_DP.dp),
            paintDefaultBackground = false,
            content = { probe.Content(window.nativeHandle) },
            driver = {
                awaitProbe(probe)
                for (size in listOf(1.0, 2.0, 1.0, 4.0)) {
                    window.setInnerSize(size, size)
                    settle(SQUEEZE_SETTLE_MILLIS)
                    check(bounds() != null) { "the window was lost at ${size}dp" }
                }
                window.setInnerSize(END_W_DP, END_H_DP)
                awaitSettledAt(probe, window, END_W_DP, END_H_DP)

                val before = probe.frames.get()
                settle(FRAME_WINDOW_MILLIS)
                check(probe.frames.get() - before >= MIN_FRAMES) {
                    "the render loop did not come back after the squeeze"
                }
            },
        )
    }

    /**
     * A window too small for its content: the layout is asked for sizes that do
     * not fit, which is where a negative measurement turns into a crash. It has
     * to lay out anyway, and be usable again once there is room.
     */
    private fun aTinyWindowStillLaysOutAndGrowsBack(): TaoWindowTestCase {
        val probe = ExtremeProbe()
        return TaoWindowTestCase(
            name = "window extremes a window too small for its content still lays out",
            timeoutMillis = LONG_CASE_TIMEOUT_MILLIS,
            size = DpSize(START_W_DP.dp, START_H_DP.dp),
            paintDefaultBackground = false,
            content = { probe.Content(window.nativeHandle, fixedChild = DpSize(BIG_CHILD_DP.dp, BIG_CHILD_DP.dp)) },
            driver = {
                awaitProbe(probe)
                window.setInnerSize(TINY_DP, TINY_DP)
                settle(SQUEEZE_SETTLE_MILLIS)
                check(bounds() != null) { "the window was lost when squeezed" }
                val child = probe.childBounds.value
                if (child != null) {
                    check(child.width >= 0f && child.height >= 0f) {
                        "the oversized child measured negative in a tiny window: $child"
                    }
                }
                window.setInnerSize(END_W_DP, END_H_DP)
                awaitSettledAt(probe, window, END_W_DP, END_H_DP)
                awaitUntil("the child is laid out again") {
                    (probe.childBounds.value?.width ?: 0f) > 0f
                }
            },
        )
    }

    // ── 2. transparency ──────────────────────────────────────────────────

    /**
     * The same storm on a fully transparent window. The clear is alpha 0 and
     * the surface is recreated on every size change, which is the combination
     * that has produced protocol errors on Wayland before.
     */
    private fun aTransparentWindowSurvivesAResizeStorm(): TaoWindowTestCase {
        val probe = ExtremeProbe(animate = true)
        return TaoWindowTestCase(
            name = "window extremes a transparent window survives a resize storm",
            timeoutMillis = LONG_CASE_TIMEOUT_MILLIS,
            transparent = true,
            paintDefaultBackground = false,
            size = DpSize(START_W_DP.dp, START_H_DP.dp),
            content = { probe.Content(window.nativeHandle, opaque = false) },
            driver = {
                awaitProbe(probe)
                stormResize(window, ROUNDS)
                window.setInnerSize(END_W_DP, END_H_DP)
                awaitSettledAt(probe, window, END_W_DP, END_H_DP)
                val before = probe.frames.get()
                settle(FRAME_WINDOW_MILLIS)
                check(probe.frames.get() - before >= MIN_FRAMES) {
                    "a transparent window stopped painting after the storm"
                }
            },
        )
    }

    /** Transparent *and* squeezed to nothing: the two together, then back. */
    private fun aTransparentWindowSqueezedToNothingKeepsPainting(): TaoWindowTestCase {
        val probe = ExtremeProbe(animate = true)
        return TaoWindowTestCase(
            name = "window extremes a transparent window squeezed to nothing keeps painting",
            timeoutMillis = LONG_CASE_TIMEOUT_MILLIS,
            transparent = true,
            paintDefaultBackground = false,
            size = DpSize(START_W_DP.dp, START_H_DP.dp),
            content = { probe.Content(window.nativeHandle, opaque = false) },
            driver = {
                awaitProbe(probe)
                repeat(SQUEEZE_ROUNDS) { round ->
                    window.setInnerSize(1.0 + round % 2, 1.0)
                    settle(SQUEEZE_SETTLE_MILLIS)
                    window.setInnerSize(END_W_DP, END_H_DP)
                    settle(SQUEEZE_SETTLE_MILLIS)
                }
                awaitSettledAt(probe, window, END_W_DP, END_H_DP)
                val before = probe.frames.get()
                settle(FRAME_WINDOW_MILLIS)
                check(probe.frames.get() - before >= MIN_FRAMES) {
                    "the transparent window stopped painting after the squeezes"
                }
            },
        )
    }

    /**
     * An animation running while the window is resized under it. The frame
     * clock drives the animation and the resize drives the surface; a resize
     * that parks the clock stops the animation for good.
     */
    private fun anAnimationKeepsRunningThroughAResizeStorm(): TaoWindowTestCase {
        val probe = ExtremeProbe(animate = true)
        return TaoWindowTestCase(
            name = "window extremes an animation keeps running through a resize storm",
            timeoutMillis = LONG_CASE_TIMEOUT_MILLIS,
            size = DpSize(START_W_DP.dp, START_H_DP.dp),
            paintDefaultBackground = false,
            content = { probe.Content(window.nativeHandle) },
            driver = {
                awaitProbe(probe)
                awaitUntil("the animation started") { probe.frames.get() > MIN_FRAMES }
                val duringStart = probe.frames.get()
                stormResize(window, ROUNDS, settleMillis = STORM_STEP_MILLIS)
                val duringEnd = probe.frames.get()
                check(duringEnd - duringStart >= MIN_FRAMES) {
                    "the animation stalled during the storm: ${duringEnd - duringStart} frames"
                }
                window.setInnerSize(END_W_DP, END_H_DP)
                awaitSettledAt(probe, window, END_W_DP, END_H_DP)
            },
        )
    }

    /**
     * Two sizes alternating as fast as they can be asked for. Each one arrives
     * while the previous is still being applied, so this is where the scene and
     * the window drift apart and stay apart.
     */
    private fun alternatingSizesNeverLeaveTheSceneBehind(): TaoWindowTestCase {
        val probe = ExtremeProbe()
        return TaoWindowTestCase(
            name = "window extremes alternating sizes never leave the scene behind the window",
            timeoutMillis = LONG_CASE_TIMEOUT_MILLIS,
            size = DpSize(START_W_DP.dp, START_H_DP.dp),
            paintDefaultBackground = false,
            content = { probe.Content(window.nativeHandle) },
            driver = {
                awaitProbe(probe)
                repeat(ALTERNATIONS) { round ->
                    window.setInnerSize(if (round % 2 == 0) SMALL_W_DP else END_W_DP, END_H_DP)
                }
                window.setInnerSize(END_W_DP, END_H_DP)
                awaitSettledAt(probe, window, END_W_DP, END_H_DP)
            },
        )
    }

    // ── 3. embedded native views ─────────────────────────────────────────

    /**
     * The rect an embedded view is given has to be the one its composable ended
     * up with — the whole point of the embed is that the platform child sits
     * exactly where Compose put the hole.
     */
    private fun aNativeViewIsPlacedWhereItsComposableEndedUp(): TaoWindowTestCase {
        val probe = ExtremeProbe(nativeView = true)
        return TaoWindowTestCase(
            name = "window extremes an embedded native view is placed where its composable ended up",
            timeoutMillis = LONG_CASE_TIMEOUT_MILLIS,
            skip = ::embedGeometrySkipReason,
            size = DpSize(START_W_DP.dp, START_H_DP.dp),
            paintDefaultBackground = false,
            content = { probe.Content(window.nativeHandle) },
            driver = {
                awaitProbe(probe)
                awaitUntil("the embed was given a rect") { probe.view.bounds() != null }
                window.setInnerSize(END_W_DP, END_H_DP)
                awaitSettledAt(probe, window, END_W_DP, END_H_DP)
                awaitUntil("the embed followed the composable") {
                    val given = probe.view.bounds() ?: return@awaitUntil false
                    val laid = probe.childBounds.value ?: return@awaitUntil false
                    abs(given.width - laid.width) <= EMBED_TOLERANCE_PX &&
                        abs(given.height - laid.height) <= EMBED_TOLERANCE_PX
                }
            },
        )
    }

    /**
     * A window with no room left for the embed. Negative or absurd rects are
     * exactly what platform APIs reject or, worse, accept and misdraw, so they
     * must never leave the host.
     */
    private fun aNativeViewNeverGetsANegativeRect(): TaoWindowTestCase {
        val probe = ExtremeProbe(nativeView = true)
        return TaoWindowTestCase(
            name = "window extremes an embedded native view is never handed a negative rect",
            timeoutMillis = LONG_CASE_TIMEOUT_MILLIS,
            skip = ::embedGeometrySkipReason,
            size = DpSize(START_W_DP.dp, START_H_DP.dp),
            paintDefaultBackground = false,
            content = { probe.Content(window.nativeHandle) },
            driver = {
                awaitProbe(probe)
                awaitUntil("the embed was given a rect") { probe.view.bounds() != null }
                for (size in listOf(TINY_DP, 1.0, 2.0, TINY_DP)) {
                    window.setInnerSize(size, size)
                    settle(SQUEEZE_SETTLE_MILLIS)
                }
                window.setInnerSize(END_W_DP, END_H_DP)
                awaitSettledAt(probe, window, END_W_DP, END_H_DP)
                val worst = probe.view.worstRect()
                check(worst == null) { "the embed was handed $worst" }
            },
        )
    }

    /**
     * Added and removed over and over — a tab switching between a document and
     * a preview. Every attach has to be matched by a detach, and the last state
     * has to be the one the composition asks for.
     */
    private fun aNativeViewAddedAndRemovedRepeatedlyIsBalanced(): TaoWindowTestCase {
        val probe = ExtremeProbe(nativeView = true)
        return TaoWindowTestCase(
            name = "window extremes an embedded native view added and removed repeatedly is balanced",
            timeoutMillis = LONG_CASE_TIMEOUT_MILLIS,
            size = DpSize(START_W_DP.dp, START_H_DP.dp),
            paintDefaultBackground = false,
            content = { probe.Content(window.nativeHandle) },
            driver = {
                awaitProbe(probe)
                awaitUntil("the first embed exists") { probe.view.created.get() == 1 }
                repeat(TOGGLES) { round ->
                    probe.showNativeView.value = false
                    awaitUntil("round $round: the embed left") { probe.view.disposed.get() == round + 1 }
                    probe.showNativeView.value = true
                    awaitUntil("round $round: a new embed arrived") { probe.view.created.get() == round + 2 }
                }
                settle(SETTLE_AFTER_MAP_MILLIS)
                check(probe.view.created.get() - probe.view.disposed.get() == 1) {
                    "created ${probe.view.created.get()} embeds, disposed ${probe.view.disposed.get()}"
                }
                check(bounds() != null) { "the window did not survive the toggling" }
            },
        )
    }

    /** The embed's rect through a storm: never negative, and correct at the end. */
    private fun aNativeViewSurvivesAResizeStormAndKeepsItsRect(): TaoWindowTestCase {
        val probe = ExtremeProbe(nativeView = true, animate = true)
        return TaoWindowTestCase(
            name = "window extremes an embedded native view survives a resize storm",
            timeoutMillis = LONG_CASE_TIMEOUT_MILLIS,
            skip = ::embedGeometrySkipReason,
            size = DpSize(START_W_DP.dp, START_H_DP.dp),
            paintDefaultBackground = false,
            content = { probe.Content(window.nativeHandle) },
            driver = {
                awaitProbe(probe)
                awaitUntil("the embed was given a rect") { probe.view.bounds() != null }
                stormResize(window, ROUNDS)
                window.setInnerSize(END_W_DP, END_H_DP)
                awaitSettledAt(probe, window, END_W_DP, END_H_DP)
                check(probe.view.worstRect() == null) { "the storm handed the embed ${probe.view.worstRect()}" }
                awaitUntil("the embed caught up with the composable") {
                    val given = probe.view.bounds() ?: return@awaitUntil false
                    val laid = probe.childBounds.value ?: return@awaitUntil false
                    abs(given.width - laid.width) <= EMBED_TOLERANCE_PX
                }
                val before = probe.frames.get()
                settle(FRAME_WINDOW_MILLIS)
                check(probe.frames.get() - before >= MIN_FRAMES) { "the loop stopped with an embed on screen" }
            },
        )
    }

    /** An embed inside a transparent window: the hole-punch and the alpha clear at once. */
    private fun aNativeViewInATransparentWindowIsStillPlaced(): TaoWindowTestCase {
        val probe = ExtremeProbe(nativeView = true)
        return TaoWindowTestCase(
            name = "window extremes an embedded native view in a transparent window is still placed",
            timeoutMillis = LONG_CASE_TIMEOUT_MILLIS,
            skip = ::embedGeometrySkipReason,
            transparent = true,
            paintDefaultBackground = false,
            size = DpSize(START_W_DP.dp, START_H_DP.dp),
            content = { probe.Content(window.nativeHandle, opaque = false) },
            driver = {
                awaitProbe(probe)
                awaitUntil("the embed was given a rect") { probe.view.bounds() != null }
                window.setInnerSize(END_W_DP, END_H_DP)
                awaitSettledAt(probe, window, END_W_DP, END_H_DP)
                awaitUntil("the embed followed") {
                    val given = probe.view.bounds() ?: return@awaitUntil false
                    val laid = probe.childBounds.value ?: return@awaitUntil false
                    abs(given.width - laid.width) <= EMBED_TOLERANCE_PX
                }
                check(probe.view.worstRect() == null) { "the embed was handed ${probe.view.worstRect()}" }
            },
        )
    }

    // ── 4. external textures ─────────────────────────────────────────────

    /**
     * A `TextureView` with nothing behind it — the state every app is in before
     * its producer is ready. It has to be an ordinary empty box, through
     * resizes and all.
     */
    private fun aTextureViewWithoutASourceIsHarmless(): TaoWindowTestCase {
        val probe = ExtremeProbe(textureView = true, animate = true)
        return TaoWindowTestCase(
            name = "window extremes a texture view with no source is an ordinary empty box",
            timeoutMillis = LONG_CASE_TIMEOUT_MILLIS,
            size = DpSize(START_W_DP.dp, START_H_DP.dp),
            paintDefaultBackground = false,
            content = { probe.Content(window.nativeHandle) },
            driver = {
                awaitProbe(probe)
                stormResize(window, ROUNDS)
                window.setInnerSize(END_W_DP, END_H_DP)
                awaitSettledAt(probe, window, END_W_DP, END_H_DP)
                val before = probe.frames.get()
                settle(FRAME_WINDOW_MILLIS)
                check(probe.frames.get() - before >= MIN_FRAMES) {
                    "a source-less texture view stopped the loop"
                }
            },
        )
    }

    /** The texture view coming and going while the window resizes under it. */
    private fun aTextureViewAppearingAndDisappearingDuringAStorm(): TaoWindowTestCase {
        val probe = ExtremeProbe(textureView = true, animate = true)
        return TaoWindowTestCase(
            name = "window extremes a texture view appearing and disappearing during a resize storm",
            timeoutMillis = LONG_CASE_TIMEOUT_MILLIS,
            size = DpSize(START_W_DP.dp, START_H_DP.dp),
            paintDefaultBackground = false,
            content = { probe.Content(window.nativeHandle) },
            driver = {
                awaitProbe(probe)
                repeat(TOGGLES) { round ->
                    probe.showTextureView.value = round % 2 == 0
                    window.setInnerSize(if (round % 2 == 0) SMALL_W_DP else END_W_DP, END_H_DP)
                    settle(STORM_STEP_MILLIS)
                }
                probe.showTextureView.value = true
                window.setInnerSize(END_W_DP, END_H_DP)
                awaitSettledAt(probe, window, END_W_DP, END_H_DP)
                val before = probe.frames.get()
                settle(FRAME_WINDOW_MILLIS)
                check(probe.frames.get() - before >= MIN_FRAMES) { "the loop stopped after the toggling" }
            },
        )
    }

    /**
     * A producer signalling frames far faster than the display: the signal is
     * meant to invalidate the draw pass, not to queue work without bound. The
     * loop has to stay responsive and the window has to stay usable.
     */
    private fun aTextureViewSignalledFasterThanTheLoopDoesNotStarveIt(): TaoWindowTestCase {
        val probe = ExtremeProbe(textureView = true, animate = true)
        return TaoWindowTestCase(
            name = "window extremes a texture signalled faster than the loop does not starve it",
            timeoutMillis = LONG_CASE_TIMEOUT_MILLIS,
            size = DpSize(START_W_DP.dp, START_H_DP.dp),
            paintDefaultBackground = false,
            content = { probe.Content(window.nativeHandle) },
            driver = {
                awaitProbe(probe)
                awaitUntil("the loop is ticking") { probe.frames.get() > MIN_FRAMES }
                val before = probe.frames.get()
                repeat(SIGNAL_STORM) { probe.controller.value?.markFrameAvailable() }
                settle(FRAME_WINDOW_MILLIS)
                val after = probe.frames.get()
                check(after - before >= MIN_FRAMES) {
                    "the signal storm starved the loop: ${after - before} frames"
                }
                check(bounds() != null) { "the window did not survive the signal storm" }
            },
        )
    }

    // ── 5. the workspaces at extreme sizes ───────────────────────────────

    /**
     * A tab window shrunk below the width of its own strip. The slots the strip
     * publishes are what turn a pointer position into an insertion index, so
     * they have to stay describable — never wider than the window, never
     * crossing — and come back when there is room again.
     */
    private fun aTabStripInAWindowTooSmallForItStaysConsistent(): TaoWindowTestCase {
        val titles = listOf("Alpha", "Beta", "Gamma", "Delta")
        val fixture = TabWorkspaceFixture(initialTitles = titles)
        return TaoWindowTestCase(
            name = "window extremes a tab strip in a window too small for it stays consistent",
            timeoutMillis = LONG_CASE_TIMEOUT_MILLIS,
            windowState = idleCaseWindowState(),
            size = idleCaseWindowSize(),
            paintDefaultBackground = false,
            applicationContent = { with(fixture) { Windows() } },
            driver = {
                val tabWindow = awaitTabSlots(fixture, *titles.toTypedArray())
                val group = requireNotNull(fixture.groupOf("Alpha"))

                for (width in listOf(SMALL_W_DP, TINY_DP, 1.0, SMALL_W_DP)) {
                    tabWindow.setInnerSize(width, STRIP_H_DP)
                    settle(SQUEEZE_SETTLE_MILLIS)
                    val slots = group.slotsInWindowPx
                    check(slots.size <= group.ids.size) {
                        "the strip published ${slots.size} slots for ${group.ids.size} tabs at ${width}dp"
                    }
                    check(slots.all { it.width >= 0f }) { "a slot measured negative at ${width}dp: $slots" }
                    check(
                        slots.zipWithNext().all { (left, right) -> left.left <= right.left },
                    ) { "slots crossed at ${width}dp: $slots" }
                }

                tabWindow.setInnerSize(WIDE_W_DP, STRIP_H_DP)
                awaitUntil("the strip is usable again") {
                    val slots = group.slotsInWindowPx
                    slots.size == group.ids.size && slots.all { it.width > 1f }
                }
                settle(SETTLE_AFTER_MAP_MILLIS)
                check(fixture.workspace.groups.size == 1) { "squeezing the window moved a tab" }
                check(group.ids.size == titles.size) { "squeezing the window lost a tab: ${group.ids}" }
            },
        )
    }

    /**
     * The parent resized under a satellite as fast as it can be asked for. The
     * satellite holds an offset from the parent's *top-left*, so a resize that
     * does not move the origin must not move it — and one that does must.
     */
    private fun aSatelliteKeepsItsOffsetThroughAResizeStorm(): TaoWindowTestCase {
        val fixture = SatelliteWorkspaceFixture()
        return TaoWindowTestCase(
            name = "window extremes a satellite keeps its offset through a resize storm",
            timeoutMillis = LONG_CASE_TIMEOUT_MILLIS,
            skip = ::workspaceSkipReason,
            windowState = workspaceParentWindowState(),
            size = DpSize(PARENT_W_DP.dp, PARENT_H_DP.dp),
            paintDefaultBackground = false,
            content = { fixture.Body() },
            applicationContent = { with(fixture) { ToolsSatellite() } },
            driver = {
                val satellite = awaitFloating(fixture)
                val parentBefore = requireNotNull(bounds())
                val satelliteBefore = requireNotNull(satellite.outerBoundsPx())
                val offsetX = satelliteBefore[0] - parentBefore[0]
                val offsetY = satelliteBefore[1] - parentBefore[1]

                repeat(ROUNDS) { round ->
                    val w = PARENT_W_DP - (round % STORM_SPAN) * STORM_STEP_DP
                    window.setInnerSize(w.toDouble(), PARENT_H_DP.toDouble())
                }
                window.setInnerSize(PARENT_W_DP.toDouble(), PARENT_H_DP.toDouble())
                settle(SETTLE_AFTER_MAP_MILLIS)

                awaitUntil("the satellite is still at its offset from the parent") {
                    val parentNow = bounds() ?: return@awaitUntil false
                    val satelliteNow = satellite.outerBoundsPx() ?: return@awaitUntil false
                    abs((satelliteNow[0] - parentNow[0]) - offsetX) <= STORM_FOLLOW_TOLERANCE_PX &&
                        abs((satelliteNow[1] - parentNow[1]) - offsetY) <= STORM_FOLLOW_TOLERANCE_PX
                }
                check(requireNotNull(satellite.outerBoundsPx())[RECT_W] > 0L) {
                    "the satellite lost its size in the storm"
                }
            },
        )
    }

    // ── the probe ────────────────────────────────────────────────────────

    /**
     * The content every case above composes: the scene size it is laid out in,
     * a frame counter, and — on demand — an embedded native view or a texture
     * view to put under the same pressure.
     */
    private class ExtremeProbe(
        private val animate: Boolean = false,
        private val nativeView: Boolean = false,
        private val textureView: Boolean = false,
    ) {
        /** The scene's container size, as Compose lays the content out in it. */
        val sceneSize = mutableStateOf(IntSize.Zero)

        /** Bounds of the probe's child, in window px. */
        val childBounds = mutableStateOf<Size?>(null)

        /** Frame-clock ticks since the content was composed. */
        val frames = AtomicLong()

        val showNativeView = mutableStateOf(true)
        val showTextureView = mutableStateOf(true)
        val controller = mutableStateOf<dev.nucleusframework.window.tao.TextureViewController?>(null)
        val view = EmbedRecorder()

        @Composable
        fun Content(
            hostHandle: Long,
            opaque: Boolean = true,
            fixedChild: DpSize? = null,
        ) {
            val container = LocalWindowInfo.current.containerSize
            SideEffect { sceneSize.value = container }
            if (animate) FrameTicker(frames)
            val childModifier =
                (if (fixedChild != null) Modifier.size(fixedChild) else Modifier.fillMaxSize())
                    .onGloballyPositioned { childBounds.value = it.boundsInWindow().size }
            Box(
                Modifier
                    .fillMaxSize()
                    .background(if (opaque) Color.DarkGray else Color.Transparent),
            ) {
                when {
                    nativeView && showNativeView.value ->
                        NativeView(factory = { view.create(hostHandle) }, modifier = childModifier)
                    textureView && showTextureView.value -> {
                        val live =
                            dev.nucleusframework.window.tao
                                .rememberTextureViewController()
                        SideEffect { controller.value = live }
                        TextureView(source = null, modifier = childModifier, controller = live)
                    }
                    else -> Box(childModifier.background(Color(0xFF2D6CDF)))
                }
            }
        }
    }

    /**
     * A frame-clock loop whose phase is read in `drawBehind`, so each tick
     * invalidates the draw layer and the host schedules the next frame. Without
     * the read the clock parks — the host only ticks it when it renders.
     */
    @Composable
    private fun FrameTicker(frames: AtomicLong) {
        val phase = remember { mutableFloatStateOf(0f) }
        Box(
            Modifier.fillMaxSize().drawBehind {
                @Suppress("UNUSED_EXPRESSION")
                phase.value
            },
        )
        LaunchedEffect(Unit) {
            while (true) {
                withFrameNanos {
                    frames.incrementAndGet()
                    phase.value = (phase.value + 1f) % PHASE_WRAP
                }
            }
        }
    }

    /**
     * A platform view of whatever kind this OS embeds, with no real native
     * handle behind it: every host guards a zero handle, so nothing is mounted
     * and what is exercised is the host's own geometry, region and lifecycle
     * bookkeeping — which is where the resize storms bite.
     */
    private class EmbedRecorder {
        val created = AtomicInteger()
        val disposed = AtomicInteger()

        private val lastBounds = mutableStateOf<Size?>(null)
        private val worst = mutableStateOf<String?>(null)
        private var nsChild: Long? = null

        fun bounds(): Size? = lastBounds.value

        /** The first rect that no platform would accept, or `null` when every one was sane. */
        fun worstRect(): String? = worst.value

        fun create(parentHandle: Long): NucleusPlatformView {
            created.incrementAndGet()
            val onBounds: (Int, Int, Int, Int) -> Unit = { x, y, w, h ->
                if (w < 0 || h < 0 || x < MIN_EMBED_COORD_PX || y < MIN_EMBED_COORD_PX) {
                    if (worst.value == null) worst.value = "rect(x=$x, y=$y, w=$w, h=$h)"
                }
                lastBounds.value = Size(w.toFloat(), h.toFloat())
            }
            val onResize: (Int, Int) -> Unit = { w, h ->
                if (w < 0 || h < 0) {
                    if (worst.value == null) worst.value = "size(w=$w, h=$h)"
                }
            }
            val onDispose: () -> Unit = { disposed.incrementAndGet() }
            return when (Platform.Current) {
                Platform.MacOS ->
                    nucleusNsPlatformView(
                        // A real child NSView: macOS disables the embed for a
                        // zero handle, and the geometry path is the point.
                        handle = {
                            nsChild ?: dev.nucleusframework.window.tao.ffi.NativeTaoMacOsNativeViewBridge
                                .nativeCreateOverlay(parentHandle)
                                .also { nsChild = it }
                        },
                        onResize = onResize,
                        onSetBounds = onBounds,
                        onDispose = onDispose,
                    )
                Platform.Windows ->
                    nucleusHwndPlatformView(
                        handle = { 0L },
                        onResize = onResize,
                        onSetBounds = onBounds,
                        onDispose = onDispose,
                    )
                else ->
                    nucleusGtkPlatformView(
                        handle = { 0L },
                        onResize = onResize,
                        onSetBounds = onBounds,
                        onDispose = onDispose,
                    )
            }
        }
    }

    // ── driving ──────────────────────────────────────────────────────────

    /**
     * Why an embed's geometry cannot be exercised here, or `null` when it can.
     *
     * The host disables the embed entirely for a handle it cannot use, so a
     * case about *where the child is put* needs a real one. macOS and Windows
     * can fabricate a bare child view from their own bridges; Linux has no
     * equivalent, and inventing a `GtkWidget*` would hand GTK a wild pointer.
     */
    private fun embedGeometrySkipReason(): String? =
        if (Platform.Current == Platform.Linux) {
            "no way to fabricate a GtkWidget from the test module"
        } else {
            null
        }

    private suspend fun TaoWindowTestScope.awaitProbe(probe: ExtremeProbe) {
        awaitUntil("window mapped") { bounds() != null }
        awaitUntil("the scene has a size") { probe.sceneSize.value.width > 0 }
        settle(SETTLE_AFTER_MAP_MILLIS)
    }

    /** Asks for [rounds] sizes in a row, cycling through a span of widths and heights. */
    private suspend fun TaoWindowTestScope.stormResize(
        window: dev.nucleusframework.window.tao.TaoWindow,
        rounds: Int,
        settleMillis: Long = 0,
    ) {
        repeat(rounds) { round ->
            val w = START_W_DP - (round % STORM_SPAN) * STORM_STEP_DP
            val h = START_H_DP - (round % STORM_SPAN) * STORM_STEP_DP
            window.setInnerSize(w.toDouble(), h.toDouble())
            if (settleMillis > 0) settle(settleMillis)
        }
    }

    /**
     * Waits until the window really is [wDp]×[hDp] and the scene agrees with
     * it: the two are measured independently, and the whole point of a storm is
     * to find out whether they can end up disagreeing.
     */
    private suspend fun TaoWindowTestScope.awaitSettledAt(
        probe: ExtremeProbe,
        window: dev.nucleusframework.window.tao.TaoWindow,
        wDp: Double,
        hDp: Double,
    ) {
        val scale = window.scaleFactor
        // The scene is the inner size in physical pixels, which is what
        // `setInnerSize` asks for. The outer frame carries the chrome and, on
        // a CSD desktop, a shadow margin the WM owns — comparing against it
        // would measure the decoration, not the resize.
        awaitUntil("the scene settled at ${wDp}x${hDp}dp") {
            val scene = probe.sceneSize.value
            abs(scene.width - (wDp * scale).toInt()) <= SIZE_TOLERANCE_PX
        }
        awaitUntil("the window is still mapped with a real frame") {
            val rect = window.outerBoundsPx() ?: return@awaitUntil false
            rect[RECT_W] >= probe.sceneSize.value.width - SIZE_TOLERANCE_PX && rect[RECT_H] > 0L
        }
        settle(SETTLE_AFTER_MAP_MILLIS)
    }

    private const val START_W_DP = 520.0
    private const val START_H_DP = 380.0
    private const val END_W_DP = 600.0
    private const val END_H_DP = 420.0
    private const val SMALL_W_DP = 200.0
    private const val TINY_DP = 20.0
    private const val WIDE_W_DP = 720.0
    private const val STRIP_H_DP = 200.0
    private const val BIG_CHILD_DP = 1200

    /** Widths the storm cycles through, in steps of [STORM_STEP_DP]. */
    private const val STORM_SPAN = 8
    private const val STORM_STEP_DP = 24

    private const val ROUNDS = 120
    private const val ALTERNATIONS = 80
    private const val TOGGLES = 8
    private const val SQUEEZE_ROUNDS = 4
    private const val SIGNAL_STORM = 500
    private const val STORM_STEP_MILLIS = 8L
    private const val SQUEEZE_SETTLE_MILLIS = 120L
    private const val FRAME_WINDOW_MILLIS = 400L
    private const val MIN_FRAMES = 4L
    private const val PHASE_WRAP = 1000f

    /** dp↔px rounding on both sides of a size round trip. */
    private const val SIZE_TOLERANCE_PX = 8

    private const val EMBED_TOLERANCE_PX = 8f

    /** A rect further off-window than this is a bug, not a scroll offset. */
    private const val MIN_EMBED_COORD_PX = -10_000

    private const val STORM_FOLLOW_TOLERANCE_PX = 24L
    private const val LONG_CASE_TIMEOUT_MILLIS = 120_000L
}
