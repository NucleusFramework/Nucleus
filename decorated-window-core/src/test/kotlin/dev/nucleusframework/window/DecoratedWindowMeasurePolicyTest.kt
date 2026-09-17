package dev.nucleusframework.window

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class DecoratedWindowMeasurePolicyTest {
    @Test
    fun `empty window measures to the incoming minimum constraints`() =
        runComposeUiTest {
            var width = -1
            var height = -1
            setContent {
                Layout(
                    modifier =
                        Modifier
                            .size(180.dp, 90.dp)
                            .onGloballyPositioned {
                                width = it.size.width
                                height = it.size.height
                            },
                    content = {},
                    measurePolicy = DecoratedWindowMeasurePolicy,
                )
            }
            waitForIdle()
            assertTrue(width > 0)
            assertTrue(height > 0)
        }

    @Test
    fun `title bar sits above content and the border sits on the bar edge`() =
        runComposeUiTest {
            var bar: LayoutCoordinates? = null
            var border: LayoutCoordinates? = null
            var content: LayoutCoordinates? = null
            setContent {
                MeasuredWindow(
                    onBar = { bar = it },
                    onBorder = { border = it },
                    onContent = { content = it },
                )
            }
            waitForIdle()
            val title = bar!!
            val line = border!!
            val body = content!!
            assertEquals(0f, title.positionInParent().y, 0.5f)
            assertEquals(title.size.height.toFloat(), line.positionInParent().y, 1f)
            assertTrue(
                body.positionInParent().y >= title.size.height + line.size.height - 1f,
                "content y=${body.positionInParent().y} bar=${title.size.height} border=${line.size.height}",
            )
        }
}

@Composable
private fun MeasuredWindow(
    onBar: (LayoutCoordinates) -> Unit,
    onBorder: (LayoutCoordinates) -> Unit,
    onContent: (LayoutCoordinates) -> Unit,
) {
    Layout(
        modifier = Modifier.width(240.dp).height(160.dp),
        content = {
            Box(
                Modifier
                    .layoutId(TITLE_BAR_LAYOUT_ID)
                    .height(40.dp)
                    .onGloballyPositioned(onBar),
            )
            Box(
                Modifier
                    .layoutId(TITLE_BAR_BORDER_LAYOUT_ID)
                    .height(1.dp)
                    .onGloballyPositioned(onBorder),
            )
            Box(
                Modifier
                    .fillMaxSize()
                    .onGloballyPositioned(onContent),
            )
        },
        measurePolicy = DecoratedWindowMeasurePolicy,
    )
}
