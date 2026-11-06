package dev.nucleusframework.window.tao.headful

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.nucleusframework.window.DialogTitleBar
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs

/**
 * #532 follow-up — a non-resizable `DecoratedDialog` (the default) must get
 * its requested size as *content*. Tao pins a non-resizable GTK window through
 * a size request, and on the window itself that request covered the CSD
 * shadow margins too: on Wayland a 300×200 dialog came up 248×148, and a
 * wrap-content dialog clipped its content by the same 52 px.
 *
 * The scene is measured from inside Compose: `outerBoundsPx()` includes the
 * shadow, so it reported the requested size while the content had shrunk.
 */
internal object NonResizableDialogSizeHeadfulCases {
    fun all(): List<TaoWindowTestCase> =
        listOf(
            fixedSizeDialogKeepsItsContentSize(),
            wrapContentDialogShowsAllItsContent(),
        )

    private fun fixedSizeDialogKeepsItsContentSize(): TaoWindowTestCase {
        val scene = AtomicReference<IntSize?>(null)
        return TaoWindowTestCase(
            name = "#532 non-resizable dialog keeps its requested content size",
            paintDefaultBackground = false,
            dialogSize = DpSize(DIALOG_WIDTH_DP.dp, DIALOG_HEIGHT_DP.dp),
            dialogContent = {
                Box(Modifier.fillMaxSize().background(Color.Red).onSizeChanged { scene.set(it) })
            },
        ) {
            val dialog = checkNotNull(dialogWindow) { "dialog window never published" }
            awaitUntil("dialog scene measured") { scene.get() != null }
            settle()
            val size = checkNotNull(scene.get())
            val widthDp = size.width / dialog.scaleFactor
            val heightDp = size.height / dialog.scaleFactor
            check(abs(widthDp - DIALOG_WIDTH_DP) <= TOLERANCE_DP && abs(heightDp - DIALOG_HEIGHT_DP) <= TOLERANCE_DP) {
                "expected a ${DIALOG_WIDTH_DP}x${DIALOG_HEIGHT_DP}dp scene, got ${widthDp}x${heightDp}dp " +
                    "(outer=${dialog.outerBoundsPx()?.toList()})"
            }
        }
    }

    private fun wrapContentDialogShowsAllItsContent(): TaoWindowTestCase {
        val content = AtomicReference<IntSize?>(null)
        val leftover = AtomicReference<IntSize?>(null)
        return TaoWindowTestCase(
            name = "#532 non-resizable wrap-content dialog shows all its content",
            paintDefaultBackground = false,
            dialogSize = DpSize(DIALOG_WIDTH_DP.dp, Dp.Unspecified),
            dialogContent = {
                DialogTitleBar { }
                Box(
                    Modifier
                        .size(DIALOG_WIDTH_DP.dp, CONTENT_HEIGHT_DP.dp)
                        .background(Color.Red)
                        .onSizeChanged { content.set(it) },
                )
                // Whatever the window has beyond its content: none once it wraps.
                Box(Modifier.fillMaxSize().onSizeChanged { leftover.set(it) })
            },
        ) {
            val dialog = checkNotNull(dialogWindow) { "dialog window never published" }
            awaitUntil("dialog content measured") { content.get() != null && leftover.get() != null }
            settle()
            val size = checkNotNull(content.get())
            val widthDp = size.width / dialog.scaleFactor
            val heightDp = size.height / dialog.scaleFactor
            check(abs(widthDp - DIALOG_WIDTH_DP) <= TOLERANCE_DP && abs(heightDp - CONTENT_HEIGHT_DP) <= TOLERANCE_DP) {
                "content clipped: expected ${DIALOG_WIDTH_DP}x${CONTENT_HEIGHT_DP}dp, got ${widthDp}x${heightDp}dp " +
                    "(outer=${dialog.outerBoundsPx()?.toList()})"
            }
            val blankDp = checkNotNull(leftover.get()).height / dialog.scaleFactor
            check(blankDp <= TOLERANCE_DP) {
                "dialog did not wrap its content: ${blankDp}dp of blank space below it " +
                    "(outer=${dialog.outerBoundsPx()?.toList()})"
            }
        }
    }

    private const val DIALOG_WIDTH_DP = 300f
    private const val DIALOG_HEIGHT_DP = 200f
    private const val CONTENT_HEIGHT_DP = 137f

    /** One device pixel of rounding at fractional scales. */
    private const val TOLERANCE_DP = 1f
}
