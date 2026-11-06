package dev.nucleusframework.lab.probes.system.flatpakupdates

import dev.nucleusframework.flatpak.FlatpakUpdateProgress
import dev.nucleusframework.flatpak.FlatpakUpdateState
import dev.nucleusframework.flatpak.FlatpakUpdateStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FlatpakUpdatesContractTest {
    private val a = "a1b2c3d4e5f6a7b8"
    private val b = "0f9e8d7c6b5a4938"

    @Test
    fun `statuses read as short commits`() {
        assertEquals("available: a1b2c3d4e5f6 → 0f9e8d7c6b5a", FlatpakUpdateStatus.Available(a, a, b).describe())
        assertEquals(
            "installed, restart to run it: a1b2c3d4e5f6 → 0f9e8d7c6b5a",
            FlatpakUpdateStatus.Installed(a, b).describe(),
        )
        assertEquals("up to date: a1b2c3d4e5f6", FlatpakUpdateStatus.UpToDate(a).describe())
    }

    @Test
    fun `progress reads as state, percent and operation`() {
        assertEquals(
            "installing 75 % (operation 2 of 2)",
            FlatpakUpdateProgress(FlatpakUpdateState.Installing, 0.75, 1, 2).describe(),
        )
        assertEquals("installing 0 %", FlatpakUpdateProgress(FlatpakUpdateState.Installing, 0.0, 0, 0).describe())
    }

    @Test
    fun `a new install clears the previous progress and ends not installing`() {
        val state =
            listOf(
                FlatpakUpdatesEvent.Reported(FlatpakUpdateStatus.Available(a, a, b)),
                FlatpakUpdatesEvent.InstallStarted,
                FlatpakUpdatesEvent.Progressed(FlatpakUpdateProgress(FlatpakUpdateState.Completed, 1.0, 0, 1)),
                FlatpakUpdatesEvent.InstallEnded,
                FlatpakUpdatesEvent.InstallStarted,
            ).fold(FlatpakUpdatesState(), FlatpakUpdatesReducer::reduce)
        assertTrue(state.installing)
        assertNull(state.progress)
        assertEquals(FlatpakUpdateStatus.Available(a, a, b), state.status)
        assertFalse(FlatpakUpdatesReducer.reduce(state, FlatpakUpdatesEvent.InstallEnded).installing)
    }
}
