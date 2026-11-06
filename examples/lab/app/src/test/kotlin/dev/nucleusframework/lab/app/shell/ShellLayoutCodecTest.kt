package dev.nucleusframework.lab.app.shell

import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import dev.nucleusframework.window.tao.DockSide
import dev.nucleusframework.window.tao.SatelliteLayoutSnapshot
import dev.nucleusframework.window.tao.SatellitePlacement
import dev.nucleusframework.window.tao.SatelliteSnapshot
import dev.nucleusframework.window.tao.WindowAnchor
import dev.nucleusframework.window.tao.WindowConstraintAdjustment
import dev.nucleusframework.window.tao.WindowPositioner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ShellLayoutCodecTest {
    private val layout =
        SatelliteLayoutSnapshot(
            satellites =
                mapOf(
                    ShellPane.Probes.satelliteId to
                        SatelliteSnapshot(SatellitePlacement.Docked(DockSide.Right, 1, 240.dp, 0.5f), isOpen = true),
                    ShellPane.Timeline.satelliteId to
                        SatelliteSnapshot(
                            SatellitePlacement.Floating(
                                WindowPositioner(
                                    WindowAnchor.TopLeft,
                                    WindowAnchor.TopLeft,
                                    DpOffset(40.dp, -12.dp),
                                    WindowConstraintAdjustment.Slide,
                                ),
                                DpSize(500.dp, 260.dp),
                            ),
                            isOpen = false,
                        ),
                ),
            dockExtents = mapOf(DockSide.Left to 264.dp, DockSide.Bottom to 220.dp),
        )

    @Test
    fun `a layout survives the round trip`() {
        assertEquals(layout, ShellLayoutCodec.decode(ShellLayoutCodec.encode(layout)))
    }

    @Test
    fun `open panes come from the layout, unnamed ones stay open`() {
        assertEquals(setOf(ShellPane.Probes, ShellPane.Checks), layout.openPanes())
    }

    @Test
    fun `an unreadable file decodes to the default layout`() {
        assertNull(ShellLayoutCodec.decode("{ not json"))
        assertNull(ShellLayoutCodec.decode(""))
        assertNull(ShellLayoutCodec.decode("""{"satellites":{"x":{"open":true,"docked":{"side":"Middle"}}}}"""))
        assertNull(ShellLayoutCodec.decode("""{"satellites":{"x":{"open":true}}}"""))
        assertNull(ShellLayoutCodec.decode("""{"version":99}"""))
    }
}
