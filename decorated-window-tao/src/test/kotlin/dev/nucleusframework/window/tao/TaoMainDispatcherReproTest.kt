package dev.nucleusframework.window.tao

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Reproduces https://github.com/NucleusFramework/Nucleus/issues/337
 *
 * `TaoMainDispatcherFactory` wins the `MainDispatcherFactory` ServiceLoader pick
 * (loadPriority = 100), so `Dispatchers.Main` routes to the Tao dispatcher even
 * when the Tao event loop was never started. Before the fix, dispatched blocks
 * were only drained by `pump()` on `MAIN_EVENTS_CLEARED` ticks, so any
 * `Dispatchers.Main` use without a running loop hung forever.
 */
class TaoMainDispatcherReproTest {
    @Test
    fun `Dispatchers Main resolves to Tao dispatcher`() {
        // Sanity: the ServiceLoader really did pick the Tao factory.
        assertTrue(
            Dispatchers.Main.toString().contains("Tao"),
            "Expected Dispatchers.Main to be the Tao dispatcher, was ${Dispatchers.Main}",
        )
    }

    @Test
    fun `runBlocking on Dispatchers Main completes without a running Tao loop`() {
        // Run on a separate thread so a hang fails the test via join timeout
        // instead of blocking the JUnit runner forever.
        var ran = false
        val worker =
            Thread {
                runBlocking(Dispatchers.Main) {
                    ran = true
                }
            }
        worker.isDaemon = true
        worker.start()
        worker.join(5_000)
        if (worker.isAlive) {
            worker.interrupt()
            fail("runBlocking(Dispatchers.Main) hung — issue #337 reproduced")
        }
        assertTrue(ran, "coroutine body never executed")
    }

    @Test
    fun `delay on Dispatchers Main resumes without a running Tao loop`() {
        var ran = false
        val worker =
            Thread {
                runBlocking {
                    withContext(Dispatchers.Main) {
                        delay(50)
                        ran = true
                    }
                }
            }
        worker.isDaemon = true
        worker.start()
        worker.join(5_000)
        if (worker.isAlive) {
            worker.interrupt()
            fail("delay() on Dispatchers.Main hung — issue #337 reproduced")
        }
        assertTrue(ran, "coroutine after delay never resumed")
    }
}
