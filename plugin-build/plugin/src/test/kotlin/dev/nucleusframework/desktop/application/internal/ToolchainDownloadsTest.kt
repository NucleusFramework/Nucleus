package dev.nucleusframework.desktop.application.internal

import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class ToolchainDownloadsTest {
    @Test
    fun `parallel callers in one JVM queue on the install lock instead of throwing`() {
        val base = Files.createTempDirectory("toolchain-lock").toFile()
        val threads = 8
        val pool = Executors.newFixedThreadPool(threads)
        try {
            val start = CountDownLatch(1)
            val inside = AtomicInteger()
            val maxInside = AtomicInteger()
            val futures =
                (1..threads).map {
                    pool.submit {
                        start.await()
                        ToolchainDownloads.withInstallLock(base, "node-22-linux-x64") {
                            maxInside.accumulateAndGet(inside.incrementAndGet(), ::maxOf)
                            Thread.sleep(20)
                            inside.decrementAndGet()
                        }
                    }
                }
            start.countDown()
            // A bare FileChannel.lock() threw OverlappingFileLockException here for every thread
            // but the first; get() rethrows it.
            futures.forEach { it.get(30, TimeUnit.SECONDS) }
            assertEquals(1, maxInside.get())
        } finally {
            pool.shutdownNow()
            base.deleteRecursively()
        }
    }
}
