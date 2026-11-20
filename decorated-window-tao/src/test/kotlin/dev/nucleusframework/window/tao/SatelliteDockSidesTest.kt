package dev.nucleusframework.window.tao

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.nucleusframework.window.tao.workspace.HostGeometry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * A satellite declared for some sides only ([SatelliteEntry.dockSides]): the
 * others are refused by [SatelliteWorkspace.dock], never previewed by a drag,
 * not offered as hints, and not applied from a snapshot.
 */
class SatelliteDockSidesTest {
    private val a = TaoWindow(handle = 1L)
    private val notTop = setOf(DockSide.Left, DockSide.Right, DockSide.Bottom)

    private val floating =
        SatellitePlacement.Floating(
            positioner = WindowPositioner(parentAnchor = WindowAnchor.Right, childAnchor = WindowAnchor.Left),
            size = DpSize(200.dp, 300.dp),
        )

    /** Host `a`: layout (100, 140)–(900, 700) on screen, scale 1. */
    private fun workspace(): SatelliteWorkspace =
        SatelliteWorkspace().apply {
            join(a)
            dockHosts.register(
                HostGeometry(a, outerBoundsPx = { longArrayOf(100L, 100L, 800L, 600L) }, scaleFactor = { 1f }).apply {
                    layoutBoundsInWindowPx = Rect(0f, 40f, 800f, 600f)
                    containerSizePx = IntSize(800, 600)
                },
            )
        }

    @Test
    fun `dock refuses a side the satellite was not declared for`() {
        val workspace = workspace()
        val entry = workspace.register("tools", "Tools", floating, initiallyOpen = true, dockSides = notTop)

        workspace.dock("tools", DockSide.Top)
        assertEquals(floating, entry.placement, "a refused dock changes nothing")

        workspace.dock("tools", DockSide.Left)
        assertEquals(DockSide.Left, assertIs<SatellitePlacement.Docked>(entry.placement).side)
        workspace.dock("tools", DockSide.Top)
        assertEquals(DockSide.Left, assertIs<SatellitePlacement.Docked>(entry.placement).side, "still where it was")
    }

    @Test
    fun `floating-only never docks, the preferred side follows the declaration`() {
        val workspace = workspace()
        val never = workspace.register("hud", "Hud", floating, initiallyOpen = true, dockSides = emptySet())
        workspace.dock("hud", DockSide.Right)
        assertEquals(floating, never.placement)

        val leftOnly =
            workspace.register(
                "nav",
                "Nav",
                floating,
                initiallyOpen = true,
                dockSides = setOf(DockSide.Left),
            )
        assertEquals(DockSide.Left, leftOnly.preferredDockSide, "the right side is not allowed: the first allowed one")
        val notTopEntry = workspace.register("tools", "Tools", floating, initiallyOpen = true, dockSides = notTop)
        assertEquals(DockSide.Right, notTopEntry.preferredDockSide)
    }

    @Test
    fun `a declared docked placement must name an allowed side`() {
        val workspace = workspace()
        assertFailsWith<IllegalArgumentException> {
            workspace.register(
                "tools",
                "Tools",
                SatellitePlacement.Docked(DockSide.Top),
                initiallyOpen = true,
                dockSides = notTop,
            )
        }
        val ok =
            workspace.register(
                "nav",
                "Nav",
                SatellitePlacement.Docked(DockSide.Left),
                initiallyOpen = true,
                dockSides = notTop,
            )
        assertEquals(DockSide.Left, assertIs<SatellitePlacement.Docked>(ok.placement).side)
    }

    @Test
    fun `a refused side is not hinted nor previewed, a release there keeps it floating`() {
        val workspace = workspace()
        val entry = workspace.register("tools", "Tools", floating, initiallyOpen = true, dockSides = notTop)
        assertEquals(
            listOf(DockSide.Left, DockSide.Right, DockSide.Bottom),
            hintedSides(entry, a, workspace.satellites),
        )

        // The bare edges are a target for anyone…
        val atTop = Rect(400f, 150f, 600f, 300f)
        assertEquals(DockTarget(a, DockSide.Top), workspace.dockTargetAt(atTop, atTop.center))
        // …but not for this satellite.
        assertNull(workspace.dockTargetFor(entry, atTop, atTop.center))

        val satellite = TaoWindow(handle = 3L)
        val origin =
            SatelliteDragOrigin.FloatingWindow(
                window = satellite,
                outerBoundsPx = { longArrayOf(400L, 300L, 200L, 150L) },
                move = { _, _ -> },
            )
        val session = requireNotNull(workspace.beginDrag("tools", origin, Offset(500f, 310f)))
        session.update(Offset(500f, 160f))
        assertNull(workspace.dockPreview, "the top zone is not previewed for a satellite that may not dock there")
        session.end(Offset(500f, 160f))
        assertIs<SatellitePlacement.Floating>(entry.placement)

        val again = requireNotNull(workspace.beginDrag("tools", origin, Offset(500f, 310f)))
        again.update(Offset(500f, 690f))
        assertEquals(DockTarget(a, DockSide.Bottom), workspace.dockPreview)
        again.end(Offset(500f, 690f))
        assertEquals(DockSide.Bottom, assertIs<SatellitePlacement.Docked>(entry.placement).side)
    }

    @Test
    fun `a snapshot naming a refused side leaves the placement alone`() {
        val workspace = workspace()
        val entry = workspace.register("tools", "Tools", floating, initiallyOpen = true, dockSides = notTop)
        workspace.restore(
            SatelliteLayoutSnapshot(
                satellites =
                    mapOf(
                        "tools" to SatelliteSnapshot(SatellitePlacement.Docked(DockSide.Top), isOpen = false),
                    ),
                dockExtents = emptyMap(),
            ),
        )
        assertEquals(floating, entry.placement)
        assertEquals(false, entry.isOpen, "the open state is still applied")

        workspace.restore(
            SatelliteLayoutSnapshot(
                satellites =
                    mapOf(
                        "tools" to SatelliteSnapshot(SatellitePlacement.Docked(DockSide.Left), isOpen = true),
                    ),
                dockExtents = emptyMap(),
            ),
        )
        assertEquals(DockSide.Left, assertIs<SatellitePlacement.Docked>(entry.placement).side)
    }
}
