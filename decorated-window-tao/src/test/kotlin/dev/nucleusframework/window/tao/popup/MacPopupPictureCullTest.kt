package dev.nucleusframework.window.tao.popup

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp
import dev.nucleusframework.window.tao.scene.replayPicture
import dev.nucleusframework.window.tao.scene.runTaoSceneTest
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Picture
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The macOS popup layer's frame, run end to end against a **real Compose
 * scene**: record through the production `recordSceneToPicture`, replay through
 * the production [replayPicture], read the pixels back.
 *
 * `TaoPopupSceneLayer` lays its inner scene out in owner-window coordinates
 * (`calculateLocalPosition` is the identity) and defers the translation into the
 * popup's own surface to replay time (`pictureOffset = -drawBounds.topLeft`), so
 * the recorded content sits at the popup's position inside a work-area-sized
 * scene rather than at the picture's origin. The picture's cull rect has to say
 * so, because `SkCanvas::drawPicture` quick-rejects against that rect mapped
 * through the current matrix — and the replay matrix moves an origin-rooted rect
 * clean off the drawable.
 *
 * Whether that is fatal depends on the op count, which is the subtlety this
 * class pins. Skia unrolls a picture of at most one op straight into the target
 * canvas and never consults the rect, and a Compose scene records as exactly one
 * op (a skiko `RenderNode` drawable — see [POPUP_DRAW_MARGIN_DP]'s note). A bare
 * popup therefore survived the mismatch by accident. A popup **dimmed by a
 * dialog stacked above it** does not: the layer paints those scrims into the
 * same picture ([PopupScrimRegistry.paintAbove], through
 * `TaoSceneBundle.renderOverlay`), the picture stops being unrollable, and the
 * quick-reject drops the whole frame — popup and scrim alike.
 */
class MacPopupPictureCullTest {
    /** The layer's inner scene: work-area sized, as the layer builds it. */
    private val sceneWidth = 1600
    private val sceneHeight = 1200

    /** Where Compose placed the popup inside that scene, and its inflated surface. */
    private val contentBounds = IntRect(left = 420, top = 340, right = 660, bottom = 520)
    private val drawBounds = popupDrawBounds(contentBounds, density = 1f)

    private val fill = Color.Magenta

    // ── The regression ────────────────────────────────────────────────────

    /**
     * A popup under an open dialog: two ops, so the cull rect is consulted, and
     * an origin-rooted one takes the entire frame with it.
     */
    @Test
    fun `a dimmed popup keeps its content`() {
        val pixels = renderPopupSurface(popupPictureCullRect(drawBounds), dimmed = true)
        assertNotEquals(
            CLEAR_ARGB,
            pixels.center,
            "a popup dimmed by a dialog above it must still render its content",
        )
        assertNotEquals(CLEAR_ARGB, pixels.contentTopLeft)
    }

    /** The failure mode itself, kept so the reason the rect must follow the content is documented. */
    @Test
    fun `an origin-rooted cull rect drops a dimmed popup's whole frame`() {
        val pixels = renderPopupSurface(originRootedCullRect(), dimmed = true)
        assertEquals(
            CLEAR_ARGB,
            pixels.center,
            "Skia is expected to quick-reject a cull rect the replay matrix moves off the drawable",
        )
    }

    /** Two ops is what takes the picture off Skia's unroll path. */
    @Test
    fun `a dimmed popup records more than one op`() {
        recordPopupScene(popupPictureCullRect(drawBounds), dimmed = true).use { picture ->
            assertTrue(
                picture.approximateOpCount > 1,
                "the scrim must be recorded into the same picture as the scene, got ${picture.approximateOpCount}",
            )
        }
    }

    // ── The undimmed case, and why it hid the bug ─────────────────────────

    @Test
    fun `an undimmed popup keeps its content`() {
        val pixels = renderPopupSurface(popupPictureCullRect(drawBounds), dimmed = false)
        assertEquals(
            fill.toArgb(),
            pixels.center,
            "the popup's surface must show what the scene drew, not an empty rectangle",
        )
        assertEquals(
            fill.toArgb(),
            pixels.contentTopLeft,
            "the content must land at the draw margin, not at the surface origin",
        )
    }

    /**
     * A Compose scene on its own is a single `RenderNode` drawable, which Skia
     * unrolls without ever looking at the cull rect. Pinned because it is the
     * only reason the mismatch was invisible for a plain menu — change it and
     * the undimmed case starts failing the way the dimmed one did.
     */
    @Test
    fun `a bare Compose scene records as one op and is unrolled`() {
        recordPopupScene(popupPictureCullRect(drawBounds), dimmed = false).use { picture ->
            assertEquals(1, picture.approximateOpCount)
        }
        val pixels = renderPopupSurface(originRootedCullRect(), dimmed = false)
        assertEquals(fill.toArgb(), pixels.center)
    }

    // ── Harness ───────────────────────────────────────────────────────────

    private class Pixels(
        val center: Int,
        val contentTopLeft: Int,
    )

    /** The rect the layer recorded with before the fix: the surface size at the picture origin. */
    private fun originRootedCullRect(): Rect = Rect.makeWH(drawBounds.width.toFloat(), drawBounds.height.toFloat())

    /** Records the layer's scene with [cullRect] and replays it into its surface. */
    private fun renderPopupSurface(
        cullRect: Rect,
        dimmed: Boolean,
    ): Pixels {
        recordPopupScene(cullRect, dimmed).use { picture ->
            val surface = Surface.makeRasterN32Premul(drawBounds.width, drawBounds.height)
            try {
                surface.canvas.clear(CLEAR_ARGB)
                surface.canvas.replayPicture(picture, IntOffset(-drawBounds.left, -drawBounds.top))
                val image = surface.makeImageSnapshot()
                val bitmap =
                    Bitmap().apply {
                        allocPixels(image.imageInfo)
                        image.readPixels(this)
                    }
                val margin = popupDrawMarginPx(1f)
                return Pixels(
                    center = bitmap.getColor(drawBounds.width / 2, drawBounds.height / 2),
                    contentTopLeft = bitmap.getColor(margin + PROBE_INSET_PX, margin + PROBE_INSET_PX),
                )
            } finally {
                surface.close()
            }
        }
    }

    /**
     * A scene shaped like the layer's: work-area sized, transparent everywhere
     * except the popup, which sits at [contentBounds] — where Compose's `Popup`
     * places it once `calculateLocalPosition` stops moving it. When [dimmed],
     * the layer's real overlay pass runs too, painting the scrim of a dialog
     * registered above this popup.
     */
    private fun recordPopupScene(
        cullRect: Rect,
        dimmed: Boolean,
    ): Picture {
        var picture: Picture? = null
        runTaoSceneTest(width = sceneWidth, height = sceneHeight) {
            if (dimmed) {
                val scrims = PopupScrimRegistry(onChanged = { })
                val popupToken = Any()
                scrims.register(popupToken) { null }
                scrims.register(Any()) { Color.Black.copy(alpha = SCRIM_ALPHA) }
                renderOverlay = { canvas ->
                    scrims.paintAbove(
                        popupToken,
                        canvas,
                        Rect.makeXYWH(
                            drawBounds.left.toFloat(),
                            drawBounds.top.toFloat(),
                            drawBounds.width.toFloat(),
                            drawBounds.height.toFloat(),
                        ),
                    )
                }
            }
            setContent {
                Box(Modifier.fillMaxSize()) {
                    Box(
                        Modifier
                            .offset { IntOffset(contentBounds.left, contentBounds.top) }
                            .size(contentBounds.width.dp, contentBounds.height.dp)
                            .background(fill),
                    )
                }
            }
            frameUntilIdle()
            picture = frame(cullRect = cullRect)
        }
        return requireNotNull(picture)
    }

    private companion object {
        private const val CLEAR_ARGB = 0x00000000
        private const val PROBE_INSET_PX = 4
        private const val SCRIM_ALPHA = 0.4f
    }
}
