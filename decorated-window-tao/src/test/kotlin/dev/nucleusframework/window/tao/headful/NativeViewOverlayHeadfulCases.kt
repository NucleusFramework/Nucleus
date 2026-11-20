package dev.nucleusframework.window.tao.headful

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.round
import dev.nucleusframework.core.runtime.Platform
import dev.nucleusframework.window.tao.NativeView
import dev.nucleusframework.window.tao.ffi.NativeTaoBridge
import dev.nucleusframework.window.tao.ffi.NativeTaoMacOsNativeViewBridge
import dev.nucleusframework.window.tao.nucleusNsPlatformView
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * #920 — `NativeView(placedAsOverlay = true)` mounts its view above the
 * Compose surface, where nothing Compose draws clips it. Its clip view must
 * therefore follow the bounds of the view's clipping ancestors: masking the
 * part that leaves them, hiding a view that left them entirely, and leaving
 * alone a view they do not touch. Geometry read back from AppKit; no input.
 */
internal object NativeViewOverlayHeadfulCases {
    fun all(): List<TaoWindowTestCase> = listOf(overlayIsClippedByItsAncestors())

    private fun overlayIsClippedByItsAncestors(): TaoWindowTestCase {
        val fixture = OverlayFixture()
        return TaoWindowTestCase(
            name = "#920 an overlay NativeView is clipped by its Compose ancestors",
            skip = ::macOnly,
            // The default background is a sibling that fills the window and pushes the content out of it.
            paintDefaultBackground = false,
            content = { ClippedOverlay(fixture) },
        ) {
            fixture.content = NativeTaoBridge.nativeNsViewHandle(window.handle)
            awaitUntil("the overlay is mounted") { fixture.field.get() != 0L && fixture.origin.get() != null }

            // Inside its parent: the clip view is the view's own rect and masks nothing.
            fixture.expectClip(this, "inside", INSIDE, VIEW_W, masks = false)
            fixture.expectView(this, "inside", 0)

            // Leaving through the right edge: only the part still in the parent.
            fixture.shift.intValue = PAST_RIGHT
            fixture.expectClip(this, "past the right edge", PAST_RIGHT, BOX_W - PAST_RIGHT, masks = true)
            fixture.expectView(this, "past the right edge", 0)

            // Leaving through the left edge: the view is offset inside its clip view.
            fixture.shift.intValue = PAST_LEFT
            fixture.expectClip(this, "past the left edge", 0, VIEW_W + PAST_LEFT, masks = true)
            fixture.expectView(this, "past the left edge", PAST_LEFT)

            fixture.shift.intValue = OUTSIDE
            awaitUntil("hidden outside its parent", detail = fixture::describe) { fixture.clip()?.get(HIDDEN) == 1 }

            fixture.shift.intValue = INSIDE
            fixture.expectClip(this, "back inside", INSIDE, VIEW_W, masks = false)
        }
    }

    /** A clipping box at a fixed place, and in it an overlay view `shift` px from the box's left edge. */
    @Composable
    private fun ClippedOverlay(fixture: OverlayFixture) {
        val field =
            remember {
                NativeTaoMacOsNativeViewBridge.nativeDiagCreateTextField().also(fixture.field::set)
            }
        Box(Modifier.fillMaxSize().onGloballyPositioned { fixture.origin.set(it.positionInRoot().round()) }) {
            Box(Modifier.offset { IntOffset(BOX_X, BOX_Y) }.sizePx(BOX_W, BOX_H).clipToBounds()) {
                NativeView(
                    factory = {
                        nucleusNsPlatformView(
                            handle = { field },
                            onDispose = { NativeTaoMacOsNativeViewBridge.nativeDiagReleaseView(field) },
                        )
                    },
                    modifier = Modifier.offset { IntOffset(fixture.shift.intValue, VIEW_Y) }.sizePx(VIEW_W, VIEW_H),
                    placedAsOverlay = true,
                )
            }
        }
    }

    private class OverlayFixture {
        val field = AtomicLong(0)
        val origin = AtomicReference<IntOffset?>(null)
        val shift: MutableIntState = mutableIntStateOf(INSIDE)

        /** The window's content NSView, which the clip view's rect is read against. */
        var content: Long = 0L

        fun clip(): IntArray? = NativeTaoMacOsNativeViewBridge.nativeDiagOverlayClip(content, field.get())

        fun describe(): String =
            "clip view ${clip()?.contentToString()}, " +
                "view ${NativeTaoMacOsNativeViewBridge.nativeDiagViewFrame(field.get())?.contentToString()}"

        /** The clip view spans [width] px of the view's row, from [x] px into the clipping box. */
        suspend fun expectClip(
            scope: TaoWindowTestScope,
            moment: String,
            x: Int,
            width: Int,
            masks: Boolean,
        ) {
            val origin = checkNotNull(origin.get())
            val expected =
                intArrayOf(origin.x + BOX_X + x, origin.y + BOX_Y + VIEW_Y, width, VIEW_H, 0, if (masks) 1 else 0)
            scope.awaitUntil("the clip view $moment is ${expected.contentToString()}", detail = ::describe) {
                clip().contentEquals(expected)
            }
        }

        /** The view keeps its size and sits [x] px into its clip view. */
        suspend fun expectView(
            scope: TaoWindowTestScope,
            moment: String,
            x: Int,
        ) {
            val expected = intArrayOf(x, 0, VIEW_W, VIEW_H)
            scope.awaitUntil(
                "the view $moment is ${expected.contentToString()} in its clip view",
                detail = ::describe,
            ) {
                NativeTaoMacOsNativeViewBridge.nativeDiagViewFrame(field.get()).contentEquals(expected)
            }
        }
    }

    /** An exact size in px, so the expected rects do not depend on the display's scale. */
    private fun Modifier.sizePx(
        width: Int,
        height: Int,
    ): Modifier =
        layout { measurable, _ ->
            val placeable = measurable.measure(Constraints.fixed(width, height))
            layout(width, height) { placeable.place(0, 0) }
        }

    private fun macOnly(): String? = if (Platform.Current != Platform.MacOS) "placedAsOverlay is macOS only" else null

    // Even numbers: whole points on a 2x display, so AppKit's frames read back exactly.
    private const val BOX_X = 80
    private const val BOX_Y = 120
    private const val BOX_W = 400
    private const val BOX_H = 200
    private const val VIEW_Y = 40
    private const val VIEW_W = 240
    private const val VIEW_H = 80

    // The view's offset from the box's left edge.
    private const val INSIDE = 60
    private const val PAST_RIGHT = 300
    private const val PAST_LEFT = -100
    private const val OUTSIDE = 600

    /** Index of the hidden flag in `nativeDiagOverlayClip`. */
    private const val HIDDEN = 4
}
