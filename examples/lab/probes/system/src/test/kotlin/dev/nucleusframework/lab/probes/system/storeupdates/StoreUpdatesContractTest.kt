package dev.nucleusframework.lab.probes.system.storeupdates

import dev.nucleusframework.iap.StoreUpdateCheck
import dev.nucleusframework.iap.StoreUpdateProgress
import dev.nucleusframework.iap.StoreUpdateState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StoreUpdatesContractTest {
    @Test
    fun `progress reads as state, percent and bytes`() {
        assertEquals(
            "downloading 42 % (1260 of 3000 bytes)",
            StoreUpdateProgress(StoreUpdateState.Downloading, 0.42, 1260, 3000).describe(),
        )
        assertEquals("pending 0 %", StoreUpdateProgress(StoreUpdateState.Pending, 0.0, 0, 0).describe())
    }

    @Test
    fun `a new install starts a fresh progress list and ends not installing`() {
        val first = StoreUpdateProgress(StoreUpdateState.Downloading, 0.5, 1, 2)
        val state =
            listOf(
                StoreUpdatesEvent.Checked(StoreUpdateCheck.Available("1.0.4.0", isMandatory = false)),
                StoreUpdatesEvent.InstallStarted,
                StoreUpdatesEvent.Progressed(first),
                StoreUpdatesEvent.InstallEnded,
                StoreUpdatesEvent.InstallStarted,
            ).fold(StoreUpdatesState(), StoreUpdatesReducer::reduce)
        assertTrue(state.installing)
        assertEquals(emptyList(), state.progress)
        assertEquals(StoreUpdateCheck.Available("1.0.4.0", isMandatory = false), state.check)
        assertFalse(StoreUpdatesReducer.reduce(state, StoreUpdatesEvent.InstallEnded).installing)
    }
}
