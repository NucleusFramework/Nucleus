package dev.nucleusframework.store.microsoft.internal

import dev.nucleusframework.store.microsoft.StoreErrorCode
import dev.nucleusframework.store.microsoft.StoreException
import dev.nucleusframework.store.microsoft.StoreUpdateCheck
import dev.nucleusframework.store.microsoft.StoreUpdateOptions
import dev.nucleusframework.store.microsoft.StoreUpdateProgress
import dev.nucleusframework.store.microsoft.StoreUpdateState
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** App updates from the Microsoft Store: [NativeStoreBackend.checkUpdates] and its siblings. */
class StoreUpdatesBackendTest {
    private val transport = FakeTransport()
    private val backend = NativeStoreBackend(transport)

    private fun options(block: StoreUpdateOptions.() -> Unit = {}) = StoreUpdateOptions().apply(block)

    private fun progress(
        request: Long,
        state: String,
        progress: Double,
    ) = """{"type":"updateProgress","request":$request,"state":"$state","progress":$progress,""" +
        """"bytesDownloaded":${(progress * 1000).toLong()},"bytesTotal":1000}"""

    @Test
    fun `check reports the newest version and whether any update is mandatory`() =
        runBlocking<Unit> {
            transport.answer = { ok("""{"updates":[]}""") }
            assertEquals(StoreUpdateCheck.UpToDate, backend.checkUpdates())
            assertEquals("updates.check", transport.requests.last().method)

            transport.answer = {
                ok(
                    """{"updates":[{"version":"1.0.9.0","mandatory":false},""" +
                        """{"version":"1.0.10.0","mandatory":true},{"mandatory":false}]}""",
                )
            }
            // Numeric order: 1.0.10 is newer than 1.0.9.
            assertEquals(StoreUpdateCheck.Available("1.0.10.0", isMandatory = true), backend.checkUpdates())

            transport.answer = { ok("""{"updates":[{"mandatory":false}]}""") }
            assertEquals(StoreUpdateCheck.Available(null, isMandatory = false), backend.checkUpdates())
        }

    @Test
    fun `check fails like every other store call`() =
        runBlocking<Unit> {
            transport.answer = { err("notPackaged") }
            assertEquals(StoreErrorCode.NotPackaged, assertFailsWith<StoreException> { backend.checkUpdates() }.code)
            transport.available = false
            assertEquals(StoreErrorCode.Unsupported, assertFailsWith<StoreException> { backend.checkUpdates() }.code)
        }

    @Test
    fun `install streams its own progress, ignores other requests and ends with the outcome`() =
        runBlocking<Unit> {
            transport.mode = FakeTransport.Mode.Held
            val collected =
                async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                    backend.installUpdates(options { windows { ownerWindowHandle = 0x42L } }).toList()
                }
            val request = withTimeout(5_000) { awaitHeld() }
            assertEquals("updates.install", request.method)
            assertEquals(0x42L, request.args["ownerWindow"]!!.jsonPrimitive.long)
            assertEquals(false, request.args["silent"]!!.jsonPrimitive.boolean)

            transport.event(progress(request.id, "pending", 0.0))
            transport.event(progress(request.id + 1000, "downloading", 0.9)) // another request's
            transport.event(progress(request.id, "downloading", 0.4))
            transport.event(progress(request.id, "failed", 0.4)) // no state of its own
            transport.event(progress(request.id, "installing", 0.85))
            transport.respond(request.id, ok("""{"state":"completed"}"""))

            val states = withTimeout(5_000) { collected.await() }
            assertEquals(
                listOf(
                    StoreUpdateProgress(StoreUpdateState.Pending, 0.0, 0, 1000),
                    StoreUpdateProgress(StoreUpdateState.Downloading, 0.4, 400, 1000),
                    StoreUpdateProgress(StoreUpdateState.Installing, 0.85, 850, 1000),
                    StoreUpdateProgress(StoreUpdateState.Completed, 1.0, 0, 0),
                ),
                states,
            )
        }

    @Test
    fun `install ends with UpToDate, Cancelled, or the Store's error`() =
        runBlocking<Unit> {
            transport.answer = { ok("""{"state":"upToDate"}""") }
            assertEquals(
                StoreUpdateState.UpToDate,
                backend
                    .installUpdates(options())
                    .toList()
                    .single()
                    .state,
            )

            // The user declined the Store's dialog.
            transport.answer = { err("cancelled") }
            assertEquals(
                StoreUpdateState.Cancelled,
                backend
                    .installUpdates(options())
                    .toList()
                    .single()
                    .state,
            )

            transport.answer = { err("updateBlocked", "Wi-Fi") }
            val blocked = assertFailsWith<StoreException> { backend.installUpdates(options()).toList() }
            assertEquals(StoreErrorCode.UpdateBlocked, blocked.code)

            transport.answer = { ok("""{"state":"teleported"}""") }
            assertEquals(
                StoreErrorCode.Internal,
                assertFailsWith<StoreException> { backend.installUpdates(options()).toList() }.code,
            )

            transport.answer = { ok("""{"state":"completed"}""") }
            backend.installUpdates(options { silent = true }).toList()
            assertTrue(
                transport.requests
                    .last()
                    .args["silent"]!!
                    .jsonPrimitive.boolean,
            )
        }

    @Test
    fun `cancelling the collection cancels the native request and drops its progress`() =
        runBlocking<Unit> {
            transport.mode = FakeTransport.Mode.Held
            val collected =
                async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                    backend.installUpdates(options()).toList()
                }
            val request = withTimeout(5_000) { awaitHeld() }
            collected.cancel()
            withTimeout(5_000) { while (request.id !in transport.cancels) yield() }
            // A late progress event for the cancelled request goes nowhere.
            transport.event(progress(request.id, "downloading", 0.5))
            assertEquals(0, backend.pendingCount)
        }

    @Test
    fun `openStorePage fails when Windows did not open the Store`() =
        runBlocking<Unit> {
            transport.answer = { ok("""{"opened":true,"storeId":"9MV8ZRKS4KT4"}""") }
            backend.openStorePage()
            assertEquals("updates.openStorePage", transport.requests.last().method)

            transport.answer = { ok("""{"opened":false}""") }
            assertEquals(StoreErrorCode.Platform, assertFailsWith<StoreException> { backend.openStorePage() }.code)
        }

    @Test
    fun `the unsupported backend refuses every update call`() =
        runBlocking<Unit> {
            val unsupported = UnsupportedStoreBackend("no store here")
            val check = assertFailsWith<StoreException> { unsupported.checkUpdates() }
            assertEquals(StoreErrorCode.Unsupported, check.code)
            val install = assertFailsWith<StoreException> { unsupported.installUpdates(options()).toList() }
            assertEquals(StoreErrorCode.Unsupported, install.code)
            assertIs<StoreException>(runCatching { unsupported.openStorePage() }.exceptionOrNull())
        }

    private suspend fun awaitHeld(): SeenRequest {
        while (true) {
            transport.heldRequests().firstOrNull()?.let { return it }
            yield()
        }
    }
}
