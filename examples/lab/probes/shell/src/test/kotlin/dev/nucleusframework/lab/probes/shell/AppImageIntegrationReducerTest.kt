package dev.nucleusframework.lab.probes.shell

import dev.nucleusframework.core.runtime.AppImageIntegrationResult
import dev.nucleusframework.core.runtime.AppImageIntegrationStatus
import dev.nucleusframework.lab.probes.shell.appimage.AppImageIntegrationEvent
import dev.nucleusframework.lab.probes.shell.appimage.AppImageIntegrationReducer
import dev.nucleusframework.lab.probes.shell.appimage.AppImageIntegrationState
import dev.nucleusframework.lab.probes.shell.appimage.canIntegrate
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AppImageIntegrationReducerTest {
    private val entry = File("/home/user/.local/share/applications/NucleusLab.desktop")

    @Test
    fun `a failed integrate keeps the re-read status and logs the reason`() {
        val state =
            AppImageIntegrationReducer.reduce(
                AppImageIntegrationState(),
                AppImageIntegrationEvent.IntegrateCalled(
                    AppImageIntegrationResult.Failed("managed elsewhere"),
                    AppImageIntegrationStatus.ExternallyManaged(entry),
                ),
            )
        assertEquals(AppImageIntegrationStatus.ExternallyManaged(entry), state.status)
        assertFalse(
            state.calls
                .single()
                .outcome.ok,
        )
        assertEquals(
            "managed elsewhere",
            state.calls
                .single()
                .outcome.error,
        )
    }

    @Test
    fun `only a missing or stale entry can be integrated`() {
        assertTrue(AppImageIntegrationStatus.NotIntegrated.canIntegrate)
        assertTrue(AppImageIntegrationStatus.Stale(entry).canIntegrate)
        assertFalse(AppImageIntegrationStatus.Integrated(entry).canIntegrate)
        assertFalse(AppImageIntegrationStatus.ExternallyManaged(entry).canIntegrate)
        assertFalse(AppImageIntegrationStatus.NotAppImage.canIntegrate)
    }
}
