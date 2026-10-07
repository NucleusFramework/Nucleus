package dev.nucleusframework.lab.probes.workspace

import dev.nucleusframework.lab.probes.workspace.common.ComposedData
import dev.nucleusframework.lab.probes.workspace.common.ComposedEvent
import dev.nucleusframework.lab.probes.workspace.common.ComposedReducer
import dev.nucleusframework.lab.probes.workspace.common.SatellitesObservation
import dev.nucleusframework.lab.probes.workspace.common.SessionEvent
import dev.nucleusframework.lab.probes.workspace.common.SessionState
import dev.nucleusframework.lab.probes.workspace.common.TabsObservation
import dev.nucleusframework.lab.probes.workspace.common.optionsPending
import dev.nucleusframework.lab.probes.workspace.tabs.TabsData
import dev.nucleusframework.lab.probes.workspace.tabs.TabsEvent
import dev.nucleusframework.lab.probes.workspace.tabs.TabsLive
import dev.nucleusframework.lab.probes.workspace.tabs.TabsOptions
import dev.nucleusframework.lab.probes.workspace.tabs.TabsReducer
import dev.nucleusframework.lab.probes.workspace.tabs.TabsState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReducersTest {
    private val closedTabs: TabsState = SessionState(live = TabsLive(), data = TabsData())

    @Test
    fun `declaration options are pending until the session is rebuilt with them`() {
        val opened = TabsReducer.reduce(closedTabs, SessionEvent.Opened)
        assertFalse(opened.optionsPending)
        val changed =
            TabsReducer.reduce(
                opened,
                SessionEvent.Probe(TabsEvent.OptionsChanged(TabsOptions(initialTabs = 8))),
            )
        assertTrue(changed.optionsPending)
        val reset = TabsReducer.reduce(changed, SessionEvent.Opened)
        assertFalse(reset.optionsPending)
        assertEquals(TabsOptions(initialTabs = 8), reset.data.appliedOptions)
        // Closed: nothing is pending, whatever the options say.
        assertFalse(TabsReducer.reduce(changed, SessionEvent.Ended).optionsPending)
    }

    @Test
    fun `a refusal is kept until the next successful save`() {
        val refused = TabsReducer.reduce(closedTabs, SessionEvent.Refused("nothing saved yet"))
        assertEquals("nothing saved yet", refused.notice)
        assertEquals(null, TabsReducer.reduce(refused, SessionEvent.Probe(TabsEvent.LayoutSaved("{}"))).notice)
    }

    @Test
    fun `live knobs survive the session ending`() {
        val live = TabsLive(rightToLeft = true)
        val opened = TabsReducer.reduce(closedTabs, SessionEvent.Opened)
        val changed = TabsReducer.reduce(opened, SessionEvent.LiveChanged(live))
        val ended = TabsReducer.reduce(changed, SessionEvent.Ended)
        assertFalse(ended.open)
        assertEquals(live, ended.live)
    }

    @Test
    fun `a dock saved for a window that closed can no longer be restored`() {
        val reducer = ComposedReducer<Unit>()
        var state = SessionState(live = Unit, data = ComposedData())
        state = reducer.reduce(state, SessionEvent.Probe(ComposedEvent.DockSaved("g1")))
        state = reducer.reduce(state, SessionEvent.Probe(ComposedEvent.DockSaved("g2")))
        val observed = ComposedEvent.Observed(TabsObservation(), mapOf("g2" to SatellitesObservation()))
        state = reducer.reduce(state, SessionEvent.Probe(observed))
        assertEquals(setOf("g2"), state.data.savedDocks)
    }
}
