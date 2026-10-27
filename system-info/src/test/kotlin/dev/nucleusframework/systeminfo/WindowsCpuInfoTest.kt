package dev.nucleusframework.systeminfo

import dev.nucleusframework.core.runtime.Platform
import dev.nucleusframework.systeminfo.windows.WindowsSystemInfo
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

// #786: cpuInfo() ran a WMI formatted-perf-data query on every call (~300 ms).
class WindowsCpuInfoTest {
    @Test
    fun `cpuInfo is cheap enough for a once-a-second monitor`() {
        if (Platform.Current != Platform.Windows) return
        repeat(WARM_UP_CALLS) { WindowsSystemInfo.cpuInfo() }

        val timesMs =
            List(MEASURED_CALLS) {
                Thread.sleep(SAMPLE_INTERVAL_MS)
                val start = System.nanoTime()
                assertNotNull(WindowsSystemInfo.cpuInfo())
                (System.nanoTime() - start) / 1_000_000.0
            }.sorted()

        // The fastest call is the cost itself; slower ones measure contention on a busy machine
        val best = timesMs.first()
        println("cpuInfo(): best=%.2f ms, all=%s".format(best, timesMs.map { "%.2f".format(it) }))
        assertTrue(best < MAX_BEST_MS, "cpuInfo() took at least $best ms, expected under $MAX_BEST_MS ms")
    }

    @Test
    fun `cpuInfo reports per-CPU frequency and usage`() {
        if (Platform.Current != Platform.Windows) return
        WindowsSystemInfo.cpuInfo()
        Thread.sleep(SAMPLE_INTERVAL_MS)
        val cpu = assertNotNull(WindowsSystemInfo.cpuInfo())

        assertTrue(cpu.cpus.isNotEmpty())
        cpu.cpus.forEach {
            assertTrue(it.frequency > 0, "${it.name} frequency should be positive, was ${it.frequency}")
            assertTrue(it.cpuUsage in 0f..100f, "${it.name} usage out of range: ${it.cpuUsage}")
        }
        println(cpu.cpus.joinToString { "${it.name}=${it.frequency}MHz/%.1f%%".format(it.cpuUsage) })
    }

    private companion object {
        const val WARM_UP_CALLS = 3
        const val MEASURED_CALLS = 10
        const val SAMPLE_INTERVAL_MS = 200L
        const val MAX_BEST_MS = 50.0
    }
}
