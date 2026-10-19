package dev.nucleusframework.window.tao.headful

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.nucleusframework.window.TitleBar
import dev.nucleusframework.window.tao.LocalRequestedTitleBarHeight
import dev.nucleusframework.window.tao.TaoWindow
import java.util.concurrent.atomic.AtomicReference

/**
 * System decorations (#841): the platform draws the title bar and frame. Asserted
 * through real windows on every OS:
 *  - the outer frame is taller than the content (a native title bar / frame sits
 *    around it — on Windows and Linux the Nucleus chrome draws none of its own);
 *  - a Nucleus [TitleBar] composed inside never claims a caption height, so no
 *    native caption zone, traffic-light layout or hit band is armed from it.
 */
internal object SystemDecorationsHeadfulCases {
    fun all(): List<TaoWindowTestCase> = listOf(windowHasNativeFrame(), dialogHasNativeFrame())

    /** A native title bar is never thinner than this, at any scale (physical px). */
    private const val MIN_TITLE_BAR_PX = 16

    private fun windowHasNativeFrame(): TaoWindowTestCase {
        val contentSize = AtomicReference<IntSize?>(null)
        val requestedTitleBar = AtomicReference<Float?>(null)
        return TaoWindowTestCase(
            name = "system decorations: window gets a native frame, no Nucleus chrome (#841)",
            systemDecorations = true,
            size = DpSize(480.dp, 320.dp),
            // The suite's full-size background would take the column's height first.
            paintDefaultBackground = false,
            content = {
                val holder = LocalRequestedTitleBarHeight.current
                val windowScope = this
                Box(Modifier.fillMaxSize().onSizeChanged { contentSize.set(it) }) {
                    with(windowScope) { TitleBar { } }
                }
                SideEffect { requestedTitleBar.set(holder.value) }
            },
            driver = {
                check(window.hasSystemDecorations) { "the window must report system decorations" }
                awaitFramed(window) { contentSize.get() }
                assertNoNucleusCaption(requestedTitleBar.get())
            },
        )
    }

    private fun dialogHasNativeFrame(): TaoWindowTestCase {
        val contentSize = AtomicReference<IntSize?>(null)
        return TaoWindowTestCase(
            name = "system decorations: dialog gets a native frame (#841)",
            systemDecorations = true,
            dialogSize = DpSize(360.dp, 200.dp),
            dialogParentedToWindow = true,
            dialogContent = {
                Box(Modifier.fillMaxSize().onSizeChanged { contentSize.set(it) })
            },
            driver = {
                val dialog = awaitDialog()
                check(dialog.hasSystemDecorations) { "the dialog must report system decorations" }
                awaitFramed(dialog) { contentSize.get() }
            },
        )
    }

    /** The suite publishes the dialog before the driver runs. */
    private fun TaoWindowTestScope.awaitDialog(): TaoWindow = checkNotNull(dialogWindow) { "dialog window" }

    /**
     * Waits until the window's outer frame is known and taller than its content by at
     * least a title bar: the platform's decorations sit outside the Compose content.
     */
    private suspend fun TaoWindowTestScope.awaitFramed(
        target: TaoWindow,
        content: () -> IntSize?,
    ) {
        awaitUntil(
            "native frame around the content",
            detail = { "outer=${target.outerBoundsPx()?.toList()} content=${content()}" },
        ) {
            val outer = target.outerBoundsPx()
            val inner = content()
            outer != null &&
                inner != null &&
                inner.height > 0 &&
                outer[3] - inner.height >= MIN_TITLE_BAR_PX &&
                outer[2] >= inner.width
        }
        val outer = checkNotNull(target.outerBoundsPx())
        val inner = checkNotNull(content())
        val surface = if (target.isNativeWaylandSurface) "native Wayland" else "non-Wayland"
        println(
            "system decorations ($surface surface): outer ${outer[2]}x${outer[3]} px, content " +
                "${inner.width}x${inner.height} px (frame adds ${outer[2] - inner.width}x${outer[3] - inner.height})",
        )
    }

    private fun assertNoNucleusCaption(requested: Float?) {
        check(requested == 0f) {
            "a TitleBar in a system-decorated window must not claim a caption height (got $requested dp)"
        }
    }
}
