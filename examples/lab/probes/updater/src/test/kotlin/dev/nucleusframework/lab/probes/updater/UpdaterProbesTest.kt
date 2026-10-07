package dev.nucleusframework.lab.probes.updater

import dev.nucleusframework.lab.probes.updater.engine.CheckOutcome
import dev.nucleusframework.lab.probes.updater.engine.FeedSource
import dev.nucleusframework.lab.probes.updater.engine.NucleusUpdaterGateway
import dev.nucleusframework.lab.probes.updater.engine.UpdaterEvent
import dev.nucleusframework.lab.probes.updater.engine.UpdaterFacts
import dev.nucleusframework.lab.probes.updater.engine.UpdaterReducer
import dev.nucleusframework.lab.probes.updater.engine.UpdaterSetup
import dev.nucleusframework.lab.probes.updater.engine.UpdaterState
import dev.nucleusframework.lab.probes.updater.feed.FeedServerHost
import dev.nucleusframework.lab.probes.updater.network.HttpClientsEvent
import dev.nucleusframework.lab.probes.updater.network.HttpClientsReducer
import dev.nucleusframework.lab.probes.updater.network.HttpClientsState
import dev.nucleusframework.lab.probes.updater.network.HttpOutcome
import dev.nucleusframework.lab.probes.updater.network.LabHttpClient
import dev.nucleusframework.updater.UpdateResult
import dev.nucleusframework.updater.testing.FeedFault
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

class UpdaterProbesTest {
    @Test
    fun `a new updater drops what the previous one found`() {
        val facts =
            UpdaterFacts(
                "1.0.0",
                updateSupported = true,
                feedOverride = null,
                simulation = null,
                wasJustUpdated = false,
            )
        val state =
            listOf(
                UpdaterEvent.Checked(CheckOutcome.NotAvailable),
                UpdaterEvent.DownloadStarted(1),
                UpdaterEvent.Configured(facts),
            ).fold(UpdaterState(), UpdaterReducer::reduce)
        assertNull(state.outcome)
        assertNull(state.download)
        assertEquals(facts, state.facts)
    }

    @Test
    fun `a finished download reads 100 percent with its file`() {
        val state =
            listOf(
                UpdaterEvent.DownloadStarted(0),
                UpdaterEvent.Progressed(512, 1024, 50.0, differential = false),
                UpdaterEvent.Downloaded("/tmp/app.zip", 1_000),
            ).fold(UpdaterState(), UpdaterReducer::reduce)
        val download = assertNotNull(state.download)
        assertEquals(100.0, download.percent)
        assertEquals("/tmp/app.zip", download.file)
        assertEquals(512L, download.bytesPerSecond(now = 99_999))
    }

    @Test
    fun `http answers are listed in client order whatever order they arrive in`() {
        val state =
            listOf(
                HttpClientsEvent.Started,
                HttpClientsEvent.Answered(HttpOutcome(LabHttpClient.KtorCio, 10, status = 200)),
                HttpClientsEvent.Answered(HttpOutcome(LabHttpClient.Jdk, 20, status = 200)),
            ).fold(HttpClientsState(), HttpClientsReducer::reduce)
        assertEquals(listOf(LabHttpClient.Jdk, LabHttpClient.KtorCio), state.outcomes.map { it.client })
    }

    @Test
    fun `the gateway updates from the loopback feed and rejects a corrupted artifact`() =
        runBlocking {
            val host = FeedServerHost()
            try {
                val baseUrl = host.start()
                host.publish("99.0.0", sizeBytes = 256L * 1024)
                val gateway = NucleusUpdaterGateway()
                gateway.configure(
                    UpdaterSetup(
                        FeedSource.Redirect(baseUrl),
                        "1.0.0",
                        "latest",
                        allowPrerelease = false,
                        differential = false,
                    ),
                )

                val available = assertIs<UpdateResult.Available>(gateway.check())
                assertEquals("99.0.0", available.info.version)
                val last = gateway.download(available.info).toList().last()
                assertTrue(assertNotNull(last.file).length() == 256L * 1024)

                host.fault(FeedFault.Corrupt(10), "NucleusLab-*", times = 1)
                try {
                    gateway.download(available.info).toList()
                    fail("A corrupted artifact must not verify")
                } catch (expected: Exception) {
                    assertTrue(
                        expected.toString().contains("hecksum", ignoreCase = true) ||
                            expected.toString().contains("sha", ignoreCase = true),
                        expected.toString(),
                    )
                }
                assertTrue(host.pollRequests().isNotEmpty())
            } finally {
                host.stop()
            }
        }
}
