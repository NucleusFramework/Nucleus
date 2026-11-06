package dev.nucleusframework.flatpak.internal

import dev.nucleusframework.flatpak.FlatpakErrorCode
import dev.nucleusframework.flatpak.FlatpakUpdateProgress
import dev.nucleusframework.flatpak.FlatpakUpdateState
import dev.nucleusframework.flatpak.FlatpakUpdateStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PortalMappingTest {
    @Test
    fun `a remote commit other than the installed one is an available update`() {
        assertEquals(FlatpakUpdateStatus.Available("a", "a", "b"), statusOf("a", "a", "b"))
    }

    @Test
    fun `an installed commit other than the running one needs a restart`() {
        assertEquals(FlatpakUpdateStatus.Installed("a", "b"), statusOf("a", "b", "b"))
    }

    @Test
    fun `the same commit everywhere is up to date`() {
        assertEquals(FlatpakUpdateStatus.UpToDate("a"), statusOf("a", "a", "a"))
    }

    @Test
    fun `an unknown remote commit is not an update`() {
        // Offline, the portal reports the local commit as the remote one; an empty one is never news.
        assertEquals(FlatpakUpdateStatus.UpToDate("a"), statusOf("a", "a", ""))
    }

    @Test
    fun `running progress spans every operation`() {
        val step = progressStepOf(PortalProgress(2, 1, 50, 0, null, null))
        assertEquals(ProgressStep.Report(FlatpakUpdateProgress(FlatpakUpdateState.Installing, 0.75, 1, 2)), step)
    }

    @Test
    fun `progress before planning is zero`() {
        val step = assertIs<ProgressStep.Report>(progressStepOf(PortalProgress(0, 0, 0, 0, null, null)))
        assertEquals(0.0, step.progress.progress)
        assertTrue(!step.isFinal)
    }

    @Test
    fun `done and empty end the install`() {
        val done = assertIs<ProgressStep.Report>(progressStepOf(PortalProgress(1, 0, 100, 2, null, null)))
        assertEquals(FlatpakUpdateState.Completed, done.progress.state)
        assertTrue(done.isFinal)
        val empty = assertIs<ProgressStep.Report>(progressStepOf(PortalProgress(0, 0, 0, 1, null, null)))
        assertEquals(FlatpakUpdateState.UpToDate, empty.progress.state)
        assertTrue(empty.isFinal)
    }

    @Test
    fun `a denied update dialog is UpdateDenied`() {
        val step =
            progressStepOf(
                PortalProgress(0, 0, 0, 3, "org.freedesktop.DBus.Error.AccessDenied", "Mise à jour refusée"),
            )
        assertEquals(FlatpakErrorCode.UpdateDenied, assertIs<ProgressStep.Failed>(step).error.code)
    }

    @Test
    fun `NotSupported while planning means new permissions`() {
        val step = progressStepOf(PortalProgress(1, 0, 0, 3, "org.freedesktop.DBus.Error.NotSupported", "…"))
        assertEquals(FlatpakErrorCode.NewPermissionsRequired, assertIs<ProgressStep.Failed>(step).error.code)
    }

    @Test
    fun `NotSupported before planning means no Access portal`() {
        val step = progressStepOf(PortalProgress(0, 0, 0, 3, "org.freedesktop.DBus.Error.NotSupported", "…"))
        assertEquals(FlatpakErrorCode.PortalMissing, assertIs<ProgressStep.Failed>(step).error.code)
    }

    @Test
    fun `bridge errors keep their D-Bus name`() {
        val error = parsePortalError("org.freedesktop.DBus.Error.ServiceUnknown\nThe name is not activatable")
        assertEquals(FlatpakErrorCode.PortalMissing, error.code)
        assertEquals("org.freedesktop.DBus.Error.ServiceUnknown", error.portalError)
        assertEquals("The name is not activatable", error.message)
        assertEquals(FlatpakErrorCode.Platform, parsePortalError("org.freedesktop.DBus.Error.Failed\nboom").code)
    }
}
