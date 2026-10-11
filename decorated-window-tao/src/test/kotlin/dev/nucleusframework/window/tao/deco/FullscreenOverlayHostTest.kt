package dev.nucleusframework.window.tao.deco

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTopPositionInRootIsEqualTo
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class FullscreenOverlayHostTest {
    private val barHeight = 40.dp

    @Test
    fun `a tap reveals the bar, which hides itself after a while`() =
        runComposeUiTest {
            mainClock.autoAdvance = false
            setContent {
                val holder = FullscreenTitleBarHolder()
                holder.titleBarHeight = barHeight
                holder.content = { Box(Modifier.testTag(BAR).fillMaxWidth().height(barHeight)) }
                FullscreenOverlayHost(holder = holder, isFullscreen = true, modifier = Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxSize())
                }
            }
            mainClock.advanceTimeBy(SETTLE_MS)
            onNodeWithTag(BAR, useUnmergedTree = true).assertTopPositionInRootIsEqualTo(-barHeight)

            onRoot().performTouchInput { click(center) }
            mainClock.advanceTimeBy(SETTLE_MS)
            onNodeWithTag(BAR, useUnmergedTree = true).assertTopPositionInRootIsEqualTo(0.dp)

            mainClock.advanceTimeBy(TOUCH_REVEAL_MS + SETTLE_MS)
            onNodeWithTag(BAR, useUnmergedTree = true).assertTopPositionInRootIsEqualTo(-barHeight)
        }

    @Test
    fun `a tap outside the shown bar hides it, a tap on it does not`() =
        runComposeUiTest {
            mainClock.autoAdvance = false
            setContent {
                val holder = FullscreenTitleBarHolder()
                holder.titleBarHeight = barHeight
                holder.content = { Box(Modifier.testTag(BAR).fillMaxWidth().height(barHeight)) }
                FullscreenOverlayHost(holder = holder, isFullscreen = true, modifier = Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxSize())
                }
            }
            onRoot().performTouchInput { click(center) }
            mainClock.advanceTimeBy(SETTLE_MS)
            onRoot().performTouchInput { click(Offset(centerX, 5f)) }
            mainClock.advanceTimeBy(SETTLE_MS)
            onNodeWithTag(BAR, useUnmergedTree = true).assertTopPositionInRootIsEqualTo(0.dp)

            onRoot().performTouchInput { click(center) }
            mainClock.advanceTimeBy(SETTLE_MS)
            onNodeWithTag(BAR, useUnmergedTree = true).assertTopPositionInRootIsEqualTo(-barHeight)
        }

    @Test
    fun `the mouse still follows the top edge`() =
        runComposeUiTest {
            mainClock.autoAdvance = false
            setContent {
                val holder = FullscreenTitleBarHolder()
                holder.titleBarHeight = barHeight
                holder.content = { Box(Modifier.testTag(BAR).fillMaxWidth().height(barHeight)) }
                FullscreenOverlayHost(holder = holder, isFullscreen = true, modifier = Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxSize())
                }
            }
            onRoot().performMouseInput { moveTo(Offset(centerX, 5f)) }
            mainClock.advanceTimeBy(SETTLE_MS)
            onNodeWithTag(BAR, useUnmergedTree = true).assertTopPositionInRootIsEqualTo(0.dp)

            onRoot().performMouseInput { moveTo(center) }
            mainClock.advanceTimeBy(SETTLE_MS)
            onNodeWithTag(BAR, useUnmergedTree = true).assertTopPositionInRootIsEqualTo(-barHeight)
        }

    private companion object {
        const val BAR = "bar"

        /** Past the 200 ms slide. */
        const val SETTLE_MS = 500L

        const val TOUCH_REVEAL_MS = 3_000L
    }
}
