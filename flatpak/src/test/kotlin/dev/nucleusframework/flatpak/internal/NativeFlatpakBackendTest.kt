package dev.nucleusframework.flatpak.internal

import dev.nucleusframework.flatpak.FlatpakAvailability
import dev.nucleusframework.flatpak.FlatpakErrorCode
import dev.nucleusframework.flatpak.FlatpakException
import dev.nucleusframework.flatpak.FlatpakUpdateState
import dev.nucleusframework.flatpak.FlatpakUpdateStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Answers like the portal would, synchronously, from the calling thread. */
private class FakeBridge(
    var version: Int = 8,
    var startError: String? = null,
    var updateError: String? = null,
    /** Signals [update] sends, in order. */
    var progress: List<PortalProgress> = emptyList(),
    /** What a new monitor reports right away, as (running, local, remote). */
    var available: Triple<String, String, String>? = null,
) : FlatpakBridge {
    val listeners = ConcurrentHashMap<Long, MonitorListener>()
    val closed = CopyOnWriteArrayList<Long>()
    val updates = CopyOnWriteArrayList<String>()
    val spawned = CopyOnWriteArrayList<List<String>>()
    val opened = CopyOnWriteArrayList<String>()

    override val isAvailable = true

    override fun portalVersion() = version

    override fun startMonitor(
        id: Long,
        listener: MonitorListener,
    ): String? {
        startError?.let { return it }
        listeners[id] = listener
        available?.let { (running, local, remote) -> listener.onUpdateAvailable(running, local, remote) }
        return null
    }

    override fun update(
        id: Long,
        parentWindow: String,
    ): String? {
        updateError?.let { return it }
        updates += parentWindow
        progress.forEach { listeners.getValue(id).onProgress(it) }
        return null
    }

    override fun closeMonitor(id: Long) {
        listeners.remove(id)
        closed += id
    }

    override fun spawnLatest(
        cwd: String,
        argv: List<String>,
    ): String? {
        spawned += argv
        return null
    }

    override fun openUri(
        parentWindow: String,
        uri: String,
    ): String? {
        opened += uri
        return null
    }
}

class NativeFlatpakBackendTest {
    private fun backend(
        bridge: FakeBridge,
        insideFlatpak: Boolean = true,
    ) = NativeFlatpakBackend(bridge) { insideFlatpak }

    @Test
    fun `availability follows the sandbox and the portal version`() =
        runBlocking {
            assertEquals(FlatpakAvailability.Available, backend(FakeBridge()).availability())
            assertEquals(FlatpakAvailability.NotFlatpak, backend(FakeBridge(), insideFlatpak = false).availability())
            assertEquals(FlatpakAvailability.PortalMissing, backend(FakeBridge(version = 1)).availability())
        }

    @Test
    fun `outside a flatpak nothing reaches the portal`() =
        runBlocking {
            val bridge = FakeBridge()
            val error = assertFailsWith<FlatpakException> { backend(bridge, insideFlatpak = false).status().first() }
            assertEquals(FlatpakErrorCode.NotFlatpak, error.code)
            assertTrue(bridge.listeners.isEmpty())
        }

    @Test
    fun `status reports the monitor and closes it with the collection`() =
        runBlocking {
            val bridge = FakeBridge(available = Triple("a", "a", "b"))
            val status = backend(bridge).status().first()
            assertEquals(FlatpakUpdateStatus.Available("a", "a", "b"), status)
            waitFor { bridge.closed.size == 1 }
        }

    @Test
    fun `install reports progress until done`() =
        runBlocking {
            val bridge =
                FakeBridge(
                    progress =
                        listOf(
                            PortalProgress(1, 0, 40, 0, null, null),
                            PortalProgress(1, 0, 100, 2, null, null),
                        ),
                )
            val reports = backend(bridge).install("wayland:handle").toList()
            assertEquals(FlatpakUpdateState.Completed, reports.last().state)
            assertEquals(listOf("wayland:handle"), bridge.updates)
            waitFor { bridge.closed.size == 1 }
        }

    @Test
    fun `a failed install fails the flow and frees the next one`() =
        runBlocking {
            val bridge =
                FakeBridge(
                    progress = listOf(PortalProgress(0, 0, 0, 3, "org.freedesktop.DBus.Error.AccessDenied", "no")),
                )
            val backend = backend(bridge)
            val error = assertFailsWith<FlatpakException> { backend.install("").toList() }
            assertEquals(FlatpakErrorCode.UpdateDenied, error.code)

            bridge.progress = listOf(PortalProgress(0, 0, 0, 1, null, null))
            assertEquals(
                FlatpakUpdateState.UpToDate,
                backend
                    .install("")
                    .toList()
                    .last()
                    .state,
            )
        }

    @Test
    fun `a refused Update call closes the monitor`() =
        runBlocking {
            val bridge = FakeBridge(updateError = "org.freedesktop.DBus.Error.Failed\nAlready installing")
            val error = assertFailsWith<FlatpakException> { backend(bridge).install("").toList() }
            assertEquals(FlatpakErrorCode.Platform, error.code)
            waitFor { bridge.closed.size == 1 }
        }

    @Test
    fun `a monitor that cannot start fails with the portal error`() =
        runBlocking {
            val bridge = FakeBridge(startError = "org.freedesktop.DBus.Error.ServiceUnknown\ngone")
            val error = assertFailsWith<FlatpakException> { backend(bridge).status().first() }
            assertEquals(FlatpakErrorCode.PortalMissing, error.code)
        }

    @Test
    fun `restart and store page go through the portal`() =
        runBlocking {
            val bridge = FakeBridge()
            val backend = backend(bridge)
            backend.spawnLatest("/", listOf("/app/bin/app", "--flag"))
            backend.openUri("", "appstream://dev.example.App")
            assertEquals(listOf(listOf("/app/bin/app", "--flag")), bridge.spawned)
            assertEquals(listOf("appstream://dev.example.App"), bridge.opened)
        }

    private fun waitFor(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 2_000_000_000L
        while (!condition()) {
            check(System.nanoTime() < deadline) { "condition not met in time" }
            Thread.sleep(10)
        }
    }
}
