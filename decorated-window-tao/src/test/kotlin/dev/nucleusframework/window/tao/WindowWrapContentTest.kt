package dev.nucleusframework.window.tao

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WindowWrapContentTest {
    @Test
    fun creationSizeUsesSpecifiedAxis() {
        assertEquals(300.0, 300.dp.toWindowCreationDp(800.0))
        assertEquals(800.0, Dp.Unspecified.toWindowCreationDp(800.0))
        assertEquals(600.0, 0.dp.toWindowCreationDp(600.0))
    }

    @Test
    fun wrapHeightKeepsRequestedWidth() {
        val size =
            resolveWrapContentSize(
                wrapWidth = false,
                wrapHeight = true,
                requested = DpSize(300.dp, Dp.Unspecified),
                minimumSize = null,
                measured = IntSize(12, 160),
                scale = 2f,
            )
        assertEquals(DpSize(300.dp, 80.dp), size)
    }

    @Test
    fun wrapBothUsesMeasuredPixels() {
        val size =
            resolveWrapContentSize(
                wrapWidth = true,
                wrapHeight = true,
                requested = DpSize(Dp.Unspecified, Dp.Unspecified),
                minimumSize = null,
                measured = IntSize(200, 100),
                scale = 1f,
            )
        assertEquals(DpSize(200.dp, 100.dp), size)
    }

    @Test
    fun wrapWaitsForPositiveMeasuredAxis() {
        assertNull(
            resolveWrapContentSize(
                wrapWidth = false,
                wrapHeight = true,
                requested = DpSize(300.dp, Dp.Unspecified),
                minimumSize = null,
                measured = IntSize(300, 0),
                scale = 1f,
            ),
        )
    }

    @Test
    fun wrapHonoursMinimumSizeFloor() {
        val size =
            resolveWrapContentSize(
                wrapWidth = false,
                wrapHeight = true,
                requested = DpSize(300.dp, Dp.Unspecified),
                minimumSize = DpSize(200.dp, 120.dp),
                measured = IntSize(300, 40),
                scale = 1f,
            )
        assertEquals(DpSize(300.dp, 120.dp), size)
    }
}
