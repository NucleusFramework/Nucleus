package dev.nucleusframework.lab.probes.workspace.common

import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import dev.nucleusframework.window.tao.DockSide
import dev.nucleusframework.window.tao.SatelliteLayoutSnapshot
import dev.nucleusframework.window.tao.SatellitePlacement
import dev.nucleusframework.window.tao.SatelliteSnapshot
import dev.nucleusframework.window.tao.TabGroupSnapshot
import dev.nucleusframework.window.tao.TabLayoutSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SatelliteDiffTest {
    private fun satellite(
        id: String,
        side: DockSide? = null,
        order: Int? = if (side != null) 0 else null,
        host: String? = if (side != null) "A" else null,
        open: Boolean = true,
        offset: String? = null,
    ) = SatelliteObservation(
        id = id,
        title = id,
        isOpen = open,
        side = side,
        order = order,
        extent = null,
        weight = null,
        host = host,
        floatingOffset = offset,
        isActive = false,
        dockSides = DockSide.entries.toSet(),
        floatable = true,
        reorderable = true,
        extentRange = "80..∞",
    )

    private fun obs(
        vararg satellites: SatelliteObservation,
        owner: String? = "A",
    ) = SatellitesObservation(owner = owner, satellites = satellites.toList())

    @Test
    fun `dock, redock and undock are told apart`() {
        assertEquals(
            listOf(SatelliteChange.Docked("i", DockSide.Left, 0, "A")),
            diffSatellites(obs(satellite("i")), obs(satellite("i", DockSide.Left))),
        )
        assertEquals(
            listOf(SatelliteChange.Redocked("i", DockSide.Right, 0, "B")),
            diffSatellites(obs(satellite("i", DockSide.Left)), obs(satellite("i", DockSide.Right, host = "B"))),
        )
        assertEquals(
            listOf(SatelliteChange.Undocked("i")),
            diffSatellites(obs(satellite("i", DockSide.Left)), obs(satellite("i"))),
        )
    }

    @Test
    fun `a floating offset moving is not structural`() {
        assertTrue(
            diffSatellites(obs(satellite("i", offset = "0, 0")), obs(satellite("i", offset = "40, 12"))).isEmpty(),
        )
    }

    @Test
    fun `owner and open state changes are reported`() {
        val changes = diffSatellites(obs(satellite("i")), obs(satellite("i", open = false), owner = "B"))
        assertEquals(listOf(SatelliteChange.OwnerChanged("B"), SatelliteChange.Closed("i")), changes)
    }

    @Test
    fun `placement wording carries side, rank and host`() {
        assertEquals("docked left #2 in A", satellite("i", DockSide.Left, order = 2).placement)
        assertEquals("closed · floating @ 4, 5", satellite("i", open = false, offset = "4, 5").placement)
    }

    @Test
    fun `snapshots serialize what an app would persist`() {
        val tabs =
            TabLayoutSnapshot(
                listOf(TabGroupSnapshot("g1", listOf("a", "b"), "b", DpOffset(10.dp, 20.dp), DpSize(900.dp, 620.dp))),
            )
        assertEquals(
            "{\"groups\":[\n  {\"id\":\"g1\",\"tabs\":[\"a\",\"b\"],\"selected\":\"b\",\"position\":[10,20],\"size\":[900,620]}\n]}",
            tabs.toJson(),
        )
        val dock =
            SatelliteLayoutSnapshot(
                satellites =
                    mapOf(
                        "tools" to
                            SatelliteSnapshot(
                                SatellitePlacement.Docked(DockSide.Left, order = 1, extent = 240.dp),
                                true,
                            ),
                    ),
                dockExtents = mapOf(DockSide.Left to 280.dp),
            )
        val json = dock.toJson()
        assertTrue(
            "\"tools\":{\"open\":true,\"placement\":{\"docked\":\"Left\",\"order\":1,\"extent\":240,\"weight\":1.0}}" in
                json,
            json,
        )
        assertTrue(json.endsWith("\"dockExtents\":{\"Left\":280}}"), json)
    }
}
