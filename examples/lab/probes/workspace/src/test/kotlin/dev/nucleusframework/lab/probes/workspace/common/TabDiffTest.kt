package dev.nucleusframework.lab.probes.workspace.common

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TabDiffTest {
    private fun window(
        id: String,
        vararg tabs: String,
        selected: String? = tabs.firstOrNull(),
    ) = TabWindowObservation(id, tabs.toList(), selected, null, "900×620", true)

    private fun obs(vararg windows: TabWindowObservation) = TabsObservation(windows = windows.toList())

    @Test
    fun `first observation reports every tab and window as opened`() {
        val changes = diffTabs(TabsObservation(), obs(window("g1", "a", "b")))
        assertEquals(
            listOf(TabChange.Opened("a", "g1"), TabChange.Opened("b", "g1"), TabChange.WindowOpened("g1")),
            changes,
        )
    }

    @Test
    fun `a tab leaving for a new window is a tear-off, not a window opened`() {
        val changes = diffTabs(obs(window("g1", "a", "b")), obs(window("g1", "a"), window("g2", "b")))
        assertEquals(listOf(TabChange.TornOff("b", "g1", "g2")), changes)
    }

    @Test
    fun `a lone tab joining another strip is a merge, not a window closed`() {
        val changes = diffTabs(obs(window("g1", "a"), window("g2", "b")), obs(window("g1", "a", "b", selected = "b")))
        assertEquals(listOf(TabChange.Merged("b", "g2", "g1")), changes)
    }

    @Test
    fun `a tab moved between two surviving windows reports its new index`() {
        val before = obs(window("g1", "a", "b"), window("g2", "c", "d"))
        val after = obs(window("g1", "a"), window("g2", "c", "b", "d", selected = "b"))
        assertEquals(listOf(TabChange.Moved("b", "g1", "g2", 1)), diffTabs(before, after))
    }

    @Test
    fun `reorder compares only the tabs that stayed`() {
        val changes = diffTabs(obs(window("g1", "a", "b", "c")), obs(window("g1", "c", "a", "b", selected = "a")))
        assertEquals(listOf(TabChange.Reordered("g1", listOf("a", "b", "c"), listOf("c", "a", "b"))), changes)
        // Closing a tab shifts the others but is no reorder.
        val closed = diffTabs(obs(window("g1", "a", "b", "c")), obs(window("g1", "a", "c")))
        assertEquals(listOf(TabChange.Closed("b")), closed)
    }

    @Test
    fun `a selection change is reported, the arrival of a moved tab is not`() {
        val selected = diffTabs(obs(window("g1", "a", "b")), obs(window("g1", "a", "b", selected = "b")))
        assertEquals(listOf(TabChange.Selected("b", "g1")), selected)
        val moved =
            diffTabs(
                obs(window("g1", "a", "b"), window("g2", "c")),
                obs(window("g1", "a"), window("g2", "c", "b", selected = "b")),
            )
        assertTrue(moved.none { it is TabChange.Selected })
    }

    @Test
    fun `the last window closing with its tabs reports both`() {
        val changes = diffTabs(obs(window("g1", "a")), TabsObservation())
        assertEquals(listOf(TabChange.Closed("a"), TabChange.WindowClosed("g1")), changes)
    }
}
