package dev.nucleusframework.window.tao

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.util.Queue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Validates the hand-off between the pre-loop fallback drainer and the native
 * Tao event loop — the review findings on the issue #337 fix.
 *
 * Each test asserts the *correct* post-fix behavior; run against the pre-fix
 * code they fail, which is what confirms the findings are real.
 */
class TaoMainDispatcherHandoffTest {
    @BeforeTest
    fun setUp() = resetDispatcherState()

    @AfterTest
    fun tearDown() = resetDispatcherState()

    /**
     * Findings #2 / #3: [TaoMainDispatcher.onNativeLoopStarting] must quiesce any
     * in-flight fallback drain before returning, so no block runs on the fallback
     * thread once the native loop owns the main thread (and so Lifecycle priming,
     * which runs after it, cannot be re-poisoned by a late fallback block).
     */
    @Test
    fun `onNativeLoopStarting waits for the in-flight fallback block`() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val finished = AtomicBoolean(false)

        TaoMainDispatcher.dispatch(
            EmptyCoroutineContext,
            Runnable {
                started.countDown()
                release.await()
                finished.set(true)
            },
        )
        assertTrue(started.await(5, TimeUnit.SECONDS), "fallback block never started")

        val handoffReturned = AtomicBoolean(false)
        val handoff =
            Thread {
                TaoMainDispatcher.onNativeLoopStarting()
                handoffReturned.set(true)
            }
        handoff.start()

        // The block is still held by `release`, so a correct onNativeLoopStarting
        // must still be blocked in awaitTermination and NOT have returned.
        Thread.sleep(300)
        assertFalse(finished.get(), "test bug: block finished early")
        assertFalse(
            handoffReturned.get(),
            "onNativeLoopStarting returned before quiescing the in-flight fallback block (findings #2/#3)",
        )

        release.countDown()
        handoff.join(5_000)
        assertTrue(handoffReturned.get(), "onNativeLoopStarting never returned")
        assertTrue(finished.get(), "fallback block never finished")
    }

    /**
     * Finding #5: the fallback executor thread must be shut down once the native
     * loop takes over, not left parked for the whole app lifetime.
     */
    @Test
    fun `fallback thread is shut down after handoff`() {
        val done = CountDownLatch(1)
        TaoMainDispatcher.dispatch(EmptyCoroutineContext, Runnable { done.countDown() })
        assertTrue(done.await(5, TimeUnit.SECONDS), "fallback block never ran")

        TaoMainDispatcher.onNativeLoopStarting()

        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (fallbackThreadAlive() && System.nanoTime() < deadline) {
            Thread.sleep(20)
        }
        assertFalse(
            fallbackThreadAlive(),
            "fallback thread still alive after handoff (finding #5)",
        )
    }

    /**
     * Finding #4: a `runBlocking(Dispatchers.Main)` nested inside a block already
     * running on the (fallback) main thread must run inline instead of dead-locking
     * the single-thread executor.
     */
    @Test
    fun `nested runBlocking on Main from a Main block does not deadlock`() {
        val ok = AtomicBoolean(false)
        val done = CountDownLatch(1)
        TaoMainDispatcher.dispatch(
            EmptyCoroutineContext,
            Runnable {
                runBlocking(Dispatchers.Main) { ok.set(true) }
                done.countDown()
            },
        )
        assertTrue(
            done.await(5, TimeUnit.SECONDS),
            "nested runBlocking(Dispatchers.Main) dead-locked the fallback (finding #4)",
        )
        assertTrue(ok.get(), "nested coroutine body never ran")
    }

    private fun fallbackThreadAlive(): Boolean =
        Thread.getAllStackTraces().keys.any { it.name == FALLBACK_THREAD_NAME && it.isAlive }

    private fun resetDispatcherState() {
        val target = TaoMainDispatcher
        val cls = target.javaClass

        fun field(name: String) = runCatching { cls.getDeclaredField(name).apply { isAccessible = true } }.getOrNull()

        field("loopRunning")?.set(target, false)
        field("taoMainThread")?.set(target, null)
        (field("fallbackDrainPending")?.get(target) as? AtomicBoolean)?.set(false)
        (field("wakePending")?.get(target) as? AtomicBoolean)?.set(false)
        (field("pending")?.get(target) as? Queue<*>)?.clear()
        field("fallbackExecutor")?.let { f ->
            (f.get(target) as? ExecutorService)?.let { exec ->
                exec.shutdownNow()
                exec.awaitTermination(2, TimeUnit.SECONDS)
                f.set(target, null)
            }
        }
    }

    private companion object {
        const val FALLBACK_THREAD_NAME = "Nucleus-Tao-Main-Fallback"
    }
}
