package dev.nucleusframework.widget.windows.host

import dev.nucleusframework.widget.WidgetState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CardRevisionTest {
    private fun clickFrom(revision: Long) = mapOf(AdaptiveCardRenderer.REVISION_INPUT to revision.toString())

    @Test
    fun aClickFromTheCardDrawnAtTheCurrentRevisionRuns() {
        val state = WidgetState.decode(null)
        val drawn = CardRevision.bump(state)
        assertEquals(1, drawn)
        assertTrue(CardRevision.isCurrent(state, clickFrom(drawn)))
    }

    @Test
    fun aClickFromACardOlderThanTheStateIsOutdated() {
        val state = WidgetState.decode(null)
        val drawn = CardRevision.bump(state)
        CardRevision.bump(state) // the app saved or pushed a change since
        assertFalse(CardRevision.isCurrent(state, clickFrom(drawn)))
    }

    @Test
    fun theRevisionSurvivesTheEncodedCustomState() {
        val state = WidgetState.decode(null)
        val drawn = CardRevision.bump(state)
        // The host sends the persisted custom state back with each action.
        assertTrue(CardRevision.isCurrent(WidgetState.decode(state.encode()), clickFrom(drawn)))
    }

    @Test
    fun aCardWithoutRevisionIsAlwaysCurrent() {
        val state = WidgetState.decode(null)
        CardRevision.bump(state)
        assertTrue(CardRevision.isCurrent(state, mapOf("s:draft" to "x")))
    }
}
