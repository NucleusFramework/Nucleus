package dev.nucleusframework.systeminfo

import dev.nucleusframework.systeminfo.model.DiskConnection
import dev.nucleusframework.systeminfo.model.DiskInfo
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DiskMonitorTest {
    private fun disk(
        mountPoint: String,
        free: Long = 1,
    ) = DiskInfo(mountPoint, "ext4", mountPoint, 10, free, "SSD", false, false, mountPoint, DiskConnection.INTERNAL)

    @Test
    fun `emits the initial scan, coalesces bursts and ignores free space changes`() =
        runBlocking {
            var disks = listOf(disk("/"))
            val scans = AtomicInteger()
            val starts = AtomicInteger()
            val stops = AtomicInteger()
            val monitor =
                DiskMonitor(
                    start = { starts.incrementAndGet() > 0 },
                    stop = { stops.incrementAndGet() },
                    scan = {
                        scans.incrementAndGet()
                        disks
                    },
                )
            val emissions = Channel<List<DiskInfo>>(Channel.UNLIMITED)
            val collector = launch { monitor.changes().collect { emissions.send(it) } }

            assertEquals(listOf("/"), withTimeout(2_000) { emissions.receive() }.map { it.mountPoint })
            assertEquals(1, starts.get())

            disks = listOf(disk("/", free = 2))
            monitor.onChanged()
            assertNull(withTimeoutOrNull(500) { emissions.receive() })

            val before = scans.get()
            disks = listOf(disk("/"), disk("/media/usb"))
            repeat(20) { monitor.onChanged() }
            assertEquals(listOf("/", "/media/usb"), withTimeout(2_000) { emissions.receive() }.map { it.mountPoint })
            assertTrue(scans.get() - before <= 2, "a burst must cost at most two scans, got ${scans.get() - before}")

            collector.cancel()
            collector.join()
            assertEquals(1, stops.get())
        }

    @Test
    fun `eject arbiter allows by default, honours a refusal and bounds slow handlers`() {
        var started = 0
        var stopped = 0
        val arbiter = EjectArbiter(start = { started++ > -1 }, stop = { stopped++ }, budgetMs = 300)
        val usb = disk("/media/usb")

        assertTrue(arbiter.decide(usb))

        val refuse = arbiter.register { false }
        assertEquals(1, started)
        assertFalse(arbiter.decide(usb))
        refuse.close()
        assertEquals(1, stopped)

        val slow =
            arbiter.register {
                Thread.sleep(5_000)
                false
            }
        val begin = System.nanoTime()
        assertTrue(arbiter.decide(usb))
        assertTrue((System.nanoTime() - begin) / 1_000_000 < 2_000)
        slow.close()

        arbiter.register { error("boom") }.use { assertTrue(arbiter.decide(usb)) }
    }
}
