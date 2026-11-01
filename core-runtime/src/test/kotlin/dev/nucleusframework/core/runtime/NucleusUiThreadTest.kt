package dev.nucleusframework.core.runtime

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.EventQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class NucleusUiThreadTest {
    @After
    fun tearDown() {
        NucleusUiThread.setExecutor(null)
    }

    @Test
    fun `posts through the registered executor`() {
        val executed = mutableListOf<String>()
        NucleusUiThread.setExecutor(Executor { it.run() })

        assertTrue(NucleusUiThread.isRegistered)
        NucleusUiThread.post { executed += "first" }
        NucleusUiThread.post { executed += "second" }

        assertEquals(listOf("first", "second"), executed)
    }

    @Test
    fun `runs the block on the executor thread, never inline`() {
        val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "ui-thread-under-test") }
        try {
            NucleusUiThread.setExecutor(executor)
            val latch = CountDownLatch(1)
            val ranOn = AtomicReference<String>()

            NucleusUiThread.post {
                ranOn.set(Thread.currentThread().name)
                latch.countDown()
            }

            assertTrue(latch.await(5, TimeUnit.SECONDS))
            assertEquals("ui-thread-under-test", ranOn.get())
            assertNotEquals(Thread.currentThread().name, ranOn.get())
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `unregistering restores the awt fallback`() {
        NucleusUiThread.setExecutor(Executor { it.run() })
        NucleusUiThread.setExecutor(null)

        assertFalse(NucleusUiThread.isRegistered)
    }

    @Test
    fun `without an executor the block reaches the awt event dispatch thread`() {
        // The pre-Tao behaviour every call site had: a host that never goes
        // through nucleusApplication keeps getting its callbacks on the EDT.
        val latch = CountDownLatch(2)
        val postedOn = AtomicReference<Thread?>(null)
        val swungOn = AtomicReference<Thread?>(null)

        NucleusUiThread.post {
            postedOn.set(Thread.currentThread())
            latch.countDown()
        }
        EventQueue.invokeLater {
            swungOn.set(Thread.currentThread())
            latch.countDown()
        }

        assertTrue("the AWT EDT did not run the blocks", latch.await(10, TimeUnit.SECONDS))
        assertFalse("the test itself must not run on the EDT", EventQueue.isDispatchThread())
        assertEquals(swungOn.get(), postedOn.get())
    }
}
