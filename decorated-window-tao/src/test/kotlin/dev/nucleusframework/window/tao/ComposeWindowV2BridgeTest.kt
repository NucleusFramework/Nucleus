@file:OptIn(ExperimentalComposeUiApi::class)

package dev.nucleusframework.window.tao

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.v2.ComposeWindowV2Access
import androidx.compose.ui.window.v2.WindowState
import androidx.compose.ui.window.v2.WindowStateWithBounds
import java.awt.GraphicsEnvironment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ComposeWindowV2BridgeTest {
    @Test
    fun defaultV2StateMapsToDefaultV1Geometry() {
        val v1 = windowStateV2ToV1(WindowState())
        assertEquals(DpSize(800.dp, 600.dp), v1.size)
        assertEquals(WindowPosition.PlatformDefault, v1.position)
        assertEquals(WindowPlacement.Floating, v1.placement)
        assertFalse(v1.isMinimized)
    }

    @Test
    fun absoluteV2BoundsMapToV1WhenAwtGeometryIsAvailable() {
        if (GraphicsEnvironment.isHeadless()) return
        val v1 =
            windowStateV2ToV1(
                WindowStateWithBounds(
                    initialPosition = DpOffset(40.dp, 60.dp),
                    initialSize = DpSize(400.dp, 200.dp),
                ),
            )
        val position = v1.position
        if (position is WindowPosition.Absolute) {
            assertEquals(400.dp, v1.size.width)
            assertEquals(200.dp, v1.size.height)
            assertEquals(40.dp, position.x)
            assertEquals(60.dp, position.y)
        } else {
            // Geometry peer construction can still fail on a "non-headless"
            // environment without a usable default GraphicsConfiguration.
            assertEquals(WindowPosition.PlatformDefault, position)
        }
    }

    @Test
    fun initializedV2StateCopiesObservedBounds() {
        val v2 =
            ComposeWindowV2Access.initializedWindowState(
                "primary",
                WindowPlacement.Maximized,
                true,
                DpRect(
                    left = 10.dp,
                    top = 20.dp,
                    right = 810.dp,
                    bottom = 620.dp,
                ),
            )
        assertTrue(v2.isInitialized)
        val v1 = windowStateV2ToV1(v2)
        assertEquals(WindowPlacement.Maximized, v1.placement)
        assertTrue(v1.isMinimized)
        assertEquals(DpSize(800.dp, 600.dp), v1.size)
        val position = assertIs<WindowPosition.Absolute>(v1.position)
        assertEquals(10.dp, position.x)
        assertEquals(20.dp, position.y)
    }

    @Test
    fun unspecifiedMinSizeIsIgnored() {
        assertEquals(null, minSizeOrNull(DpSize.Unspecified))
        assertEquals(DpSize(200.dp, 100.dp), minSizeOrNull(DpSize(200.dp, 100.dp)))
    }
}
