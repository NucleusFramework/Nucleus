package dev.nucleusframework.window

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class TitleBarLayoutPolicyTest {
    @Test
    fun `default policy places start then center then end on the ltr side`() =
        runComposeUiTest {
            var start: LayoutCoordinates? = null
            var center: LayoutCoordinates? = null
            var end: LayoutCoordinates? = null
            var barWidth = 0
            var startPx = 0
            var centerPx = 0
            var endPx = 0
            setContent {
                val density = LocalDensity.current
                startPx = with(density) { 20.dp.roundToPx() }
                centerPx = with(density) { 30.dp.roundToPx() }
                endPx = with(density) { 16.dp.roundToPx() }
                WithTitleBarInfo {
                    GenericTitleBarImpl(
                        state = DecoratedWindowState.of(),
                        modifier = Modifier.width(320.dp).onSizeChanged { barWidth = it.width },
                        controlButtonsDirection = LayoutDirection.Ltr,
                        layoutPolicy = TitleBarLayoutPolicy.Default,
                        applyTitleBar = { _, _ -> PaddingValues(0.dp) },
                    ) {
                        Box(
                            Modifier
                                .size(20.dp)
                                .align(Alignment.Start)
                                .onGloballyPositioned { start = it },
                        )
                        Box(
                            Modifier
                                .size(30.dp)
                                .align(Alignment.CenterHorizontally)
                                .onGloballyPositioned { center = it },
                        )
                        Box(
                            Modifier
                                .size(16.dp)
                                .align(Alignment.End)
                                .onGloballyPositioned { end = it },
                        )
                    }
                }
            }
            waitForIdle()
            val startBox = start!!
            val centerBox = center!!
            val endBox = end!!
            val startX = startBox.positionInParent().x
            val centerX = centerBox.positionInParent().x
            val endX = endBox.positionInParent().x
            assertTrue(barWidth > 0, "title bar width was $barWidth")
            assertTrue(startX < centerX, "start $startX should be left of center $centerX")
            assertTrue(centerX < endX, "center $centerX should be left of end $endX")
            assertTrue(startX <= 1f, "start should hug the left edge, was $startX")
            assertTrue(
                endX + endBox.size.width >= barWidth - 1,
                "end should hug the right edge: end=$endX width=${endBox.size.width} bar=$barWidth",
            )
            assertEquals(startPx, startBox.size.width)
            assertEquals(centerPx, centerBox.size.width)
            assertEquals(endPx, endBox.size.width)
        }

    @Test
    fun `generic title bar default policy produces a non-zero height`() =
        runComposeUiTest {
            var heightPx = 0
            var appliedHeight = 0f
            setContent {
                WithTitleBarInfo {
                    GenericTitleBarImpl(
                        state = DecoratedWindowState.of(active = true, fullscreen = false),
                        modifier = Modifier.onSizeChanged { heightPx = it.height },
                        controlButtonsDirection = LayoutDirection.Ltr,
                        layoutPolicy = TitleBarLayoutPolicy.Default,
                        applyTitleBar = { height, _ ->
                            appliedHeight = height.value
                            PaddingValues(horizontal = 8.dp)
                        },
                    ) {
                        Box(Modifier.size(12.dp).align(Alignment.Start))
                        Box(Modifier.size(24.dp).align(Alignment.CenterHorizontally))
                        Box(Modifier.size(12.dp).align(Alignment.End))
                    }
                }
            }
            waitForIdle()
            assertTrue(heightPx > 0, "title bar height was $heightPx")
            assertTrue(appliedHeight > 0f, "applyTitleBar height was $appliedHeight")
        }

    @Test
    fun `fill center policy stretches the single center child`() =
        runComposeUiTest {
            var start: LayoutCoordinates? = null
            var center: LayoutCoordinates? = null
            var end: LayoutCoordinates? = null
            var requestedCenterPx = 0
            setContent {
                val density = LocalDensity.current
                requestedCenterPx = with(density) { 18.dp.roundToPx() }
                WithTitleBarInfo {
                    GenericTitleBarImpl(
                        state = DecoratedWindowState.of(),
                        modifier = Modifier.width(400.dp),
                        controlButtonsDirection = LayoutDirection.Ltr,
                        layoutPolicy = TitleBarLayoutPolicy.FillCenter,
                        applyTitleBar = { _, _ -> PaddingValues(4.dp) },
                    ) {
                        Box(
                            Modifier
                                .size(10.dp)
                                .align(Alignment.Start)
                                .onGloballyPositioned { start = it },
                        )
                        Box(
                            Modifier
                                .size(18.dp)
                                .align(Alignment.CenterHorizontally)
                                .onGloballyPositioned { center = it },
                        )
                        Box(
                            Modifier
                                .size(10.dp)
                                .align(Alignment.End)
                                .onGloballyPositioned { end = it },
                        )
                    }
                }
            }
            waitForIdle()
            val startBox = start!!
            val centerBox = center!!
            val endBox = end!!
            assertTrue(
                centerBox.size.width > requestedCenterPx,
                "FillCenter should stretch the center child: " +
                    "measured=${centerBox.size.width} requested=$requestedCenterPx",
            )
            assertTrue(
                startBox.positionInParent().x + startBox.size.width <= centerBox.positionInParent().x + 1f,
            )
            assertTrue(
                centerBox.positionInParent().x + centerBox.size.width <= endBox.positionInParent().x + 1f,
            )
        }

    @Test
    fun `fill center with only start and end still measures`() =
        runComposeUiTest {
            var start: Offset? = null
            var end: Offset? = null
            var heightPx = 0
            setContent {
                WithTitleBarInfo {
                    GenericTitleBarImpl(
                        state = DecoratedWindowState.of(),
                        modifier = Modifier.width(200.dp).onSizeChanged { heightPx = it.height },
                        layoutPolicy = TitleBarLayoutPolicy.FillCenter,
                        applyTitleBar = { _, _ -> PaddingValues(0.dp) },
                    ) {
                        Box(
                            Modifier
                                .size(8.dp)
                                .align(Alignment.Start)
                                .onGloballyPositioned { start = it.positionInParent() },
                        )
                        Box(
                            Modifier
                                .size(8.dp)
                                .align(Alignment.End)
                                .onGloballyPositioned { end = it.positionInParent() },
                        )
                    }
                }
            }
            waitForIdle()
            assertTrue(heightPx > 0)
            assertTrue(start!!.x < end!!.x, "start ${start!!.x} should sit left of end ${end!!.x}")
        }

    @Test
    fun `rtl content and rtl controls put start on the right and end on the left`() =
        runComposeUiTest {
            var start: Offset? = null
            var end: Offset? = null
            var placed = false
            setContent {
                CompositionLocalProvider(
                    LocalLayoutDirection provides LayoutDirection.Rtl,
                ) {
                    WithTitleBarInfo {
                        GenericTitleBarImpl(
                            state = DecoratedWindowState.of(minimized = true, maximized = true),
                            modifier = Modifier.width(240.dp),
                            controlButtonsDirection = LayoutDirection.Rtl,
                            layoutPolicy = TitleBarLayoutPolicy.Default,
                            applyTitleBar = { _, _ -> PaddingValues(2.dp) },
                            onPlace = { placed = true },
                        ) {
                            Box(
                                Modifier
                                    .size(14.dp)
                                    .align(Alignment.Start)
                                    .onGloballyPositioned { start = it.positionInParent() },
                            )
                            Box(
                                Modifier
                                    .size(14.dp)
                                    .align(Alignment.End)
                                    .onGloballyPositioned { end = it.positionInParent() },
                            )
                        }
                    }
                }
            }
            waitForIdle()
            assertTrue(placed)
            assertTrue(
                start!!.x > end!!.x,
                "RTL start should sit to the right of end controls: start=${start!!.x} end=${end!!.x}",
            )
        }

    @Test
    fun `empty measurables do not crash the measure policy`() =
        runComposeUiTest {
            var laidOut = IntSize.Zero
            setContent {
                Box(Modifier.size(12.dp).onSizeChanged { laidOut = it }) {
                    androidx.compose.ui.layout.Layout(
                        content = {},
                        measurePolicy =
                            TitleBarMeasurePolicy(
                                state = DecoratedWindowState.of(),
                                applyTitleBar = { _, _ -> PaddingValues(0.dp) },
                                controlButtonsDirection = LayoutDirection.Ltr,
                            ),
                    )
                }
            }
            waitForIdle()
            assertTrue(laidOut.width > 0)
            assertTrue(laidOut.height > 0)
        }

    @Test
    fun `decorated window measure policy rejects two title bars`() =
        runComposeUiTest {
            assertFailsWith<IllegalStateException> {
                setContent {
                    androidx.compose.ui.layout.Layout(
                        content = {
                            Box(Modifier.layoutId(TITLE_BAR_LAYOUT_ID).size(10.dp))
                            Box(Modifier.layoutId(TITLE_BAR_LAYOUT_ID).size(10.dp))
                        },
                        measurePolicy = DecoratedWindowMeasurePolicy,
                    )
                }
                waitForIdle()
            }
        }

    @Test
    fun `decorated window measure policy places one title bar above content`() =
        runComposeUiTest {
            var titleY = -1f
            var borderY = -1f
            var contentY = -1f
            var titleHeight = 0
            setContent {
                Box(Modifier.size(80.dp, 50.dp)) {
                    androidx.compose.ui.layout.Layout(
                        content = {
                            Box(
                                Modifier
                                    .layoutId(TITLE_BAR_LAYOUT_ID)
                                    .size(width = 40.dp, height = 12.dp)
                                    .onGloballyPositioned {
                                        titleY = it.positionInParent().y
                                        titleHeight = it.size.height
                                    },
                            )
                            Box(
                                Modifier
                                    .layoutId(TITLE_BAR_BORDER_LAYOUT_ID)
                                    .size(width = 40.dp, height = 1.dp)
                                    .onGloballyPositioned { borderY = it.positionInParent().y },
                            )
                            Box(
                                Modifier
                                    .size(40.dp)
                                    .onGloballyPositioned { contentY = it.positionInParent().y },
                            )
                        },
                        measurePolicy = DecoratedWindowMeasurePolicy,
                    )
                }
            }
            waitForIdle()
            assertEquals(0f, titleY)
            assertEquals(titleHeight.toFloat(), borderY)
            assertTrue(contentY >= borderY, "content y=$contentY should sit below border y=$borderY")
        }

    @Test
    fun `empty decorated window measure policy still lays out`() =
        runComposeUiTest {
            var size = IntSize.Zero
            setContent {
                Box(Modifier.size(16.dp).onSizeChanged { size = it }) {
                    androidx.compose.ui.layout.Layout(
                        content = {},
                        measurePolicy = DecoratedWindowMeasurePolicy,
                    )
                }
            }
            waitForIdle()
            assertTrue(size.width > 0)
        }

    @Test
    fun `fill center rejects two center children`() =
        runComposeUiTest {
            assertFailsWith<IllegalArgumentException> {
                setContent {
                    WithTitleBarInfo {
                        GenericTitleBarImpl(
                            state = DecoratedWindowState.of(),
                            layoutPolicy = TitleBarLayoutPolicy.FillCenter,
                            applyTitleBar = { _, _ -> PaddingValues(0.dp) },
                        ) {
                            Box(Modifier.size(8.dp).align(Alignment.CenterHorizontally))
                            Box(Modifier.size(8.dp).align(Alignment.CenterHorizontally))
                        }
                    }
                }
                waitForIdle()
            }
        }

    @Test
    fun `inactive title bar with a gradient start color still measures`() =
        runComposeUiTest {
            var heightPx = 0
            setContent {
                WithTitleBarInfo {
                    GenericTitleBarImpl(
                        state = DecoratedWindowState.of(active = false),
                        modifier = Modifier.onSizeChanged { heightPx = it.height },
                        gradientStartColor = Color.Red,
                        layoutPolicy = TitleBarLayoutPolicy.Default,
                        applyTitleBar = { _, _ -> PaddingValues(0.dp) },
                    ) {
                        Box(Modifier.size(10.dp).align(Alignment.CenterHorizontally))
                    }
                }
            }
            waitForIdle()
            assertTrue(heightPx > 0)
        }
}

@Composable
private fun WithTitleBarInfo(content: @Composable () -> Unit) {
    CompositionLocalProvider(
        LocalTitleBarInfo provides TitleBarInfo("Coverage", null),
        content = content,
    )
}
