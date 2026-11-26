package dev.nucleusframework.lab.probes.system.info

import dev.nucleusframework.lab.core.Availability
import dev.nucleusframework.systeminfo.model.CpuGlobalInfo
import dev.nucleusframework.systeminfo.model.GpuInfo
import dev.nucleusframework.systeminfo.model.MemoryInfo
import dev.nucleusframework.systeminfo.model.NetworkInterfaceInfo
import dev.nucleusframework.systeminfo.model.ProcessInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class SystemInfoReducerTest {
    private fun nic(
        name: String,
        rx: Long,
        tx: Long,
    ) = NetworkInterfaceInfo(name, rx, tx, 0, 0, 0, 0, "00:00:00:00:00:00", 1500)

    private fun process(
        pid: Long,
        name: String,
        cpu: Float,
    ) = ProcessInfo(pid, name, null, 0, 0, cpu, "Run", 0, 0, null, emptyList(), null, null)

    private fun sample(
        at: Long,
        networks: List<NetworkInterfaceInfo> = emptyList(),
        cpu: Float? = null,
        gpus: List<GpuInfo> = emptyList(),
        processes: List<ProcessInfo>? = null,
        memory: MemoryInfo? = null,
    ) = SystemSample(
        epochMillis = at,
        os = null,
        memory = memory,
        cpu = cpu?.let { CpuGlobalInfo(it, null, emptyList()) },
        disks = emptyList(),
        components = emptyList(),
        networks = networks,
        gpus = gpus,
        battery = null,
        connectivity = null,
        idleMillis = -1,
        motherboard = null,
        product = null,
        processes = processes,
    )

    private fun SystemInfoState.sampled(sample: SystemSample) =
        SystemInfoReducer.reduce(this, SystemInfoEvent.Sampled(sample, 5))

    @Test
    fun `rates are the counter delta over the time between samples`() {
        val rates =
            SystemInfoReducer.netRates(
                sample(0, listOf(nic("en0", 1_000, 0))),
                sample(2_000, listOf(nic("en0", 5_000, 2_000))),
            )
        assertEquals(NetRate(2_000f, 1_000f), rates["en0"])
    }

    @Test
    fun `a counter going backwards reads as zero, a new interface starts at zero`() {
        val rates =
            SystemInfoReducer.netRates(
                sample(0, listOf(nic("en0", 9_000, 9_000))),
                sample(1_000, listOf(nic("en0", 10, 10), nic("utun1", 500, 500))),
            )
        assertEquals(NetRate(0f, 0f), rates["en0"])
        assertEquals(NetRate(0f, 0f), rates["utun1"])
    }

    @Test
    fun `the first sample has no throughput history, the second has one point`() {
        val first = SystemInfoState().sampled(sample(0, listOf(nic("en0", 0, 0))))
        assertEquals(emptyList(), first.rxHistory)
        val second = first.sampled(sample(1_000, listOf(nic("en0", 100, 0))))
        assertEquals(listOf(100f), second.rxHistory)
    }

    @Test
    fun `histories are capped`() {
        var state = SystemInfoState()
        repeat(SystemInfoReducer.HISTORY + 10) { state = state.sampled(sample(it * 1_000L, cpu = it.toFloat())) }
        assertEquals(SystemInfoReducer.HISTORY, state.cpuHistory.size)
        assertEquals((SystemInfoReducer.HISTORY + 9).toFloat(), state.cpuHistory.last())
    }

    @Test
    fun `two identical adapters keep separate histories`() {
        val gpu = GpuInfo("Same GPU", 1, 2, 0, 0, 0, gpuUsage = 10f)
        val state = SystemInfoState().sampled(sample(0, gpus = listOf(gpu, gpu.copy(gpuUsage = 90f))))
        assertEquals(listOf(10f), state.gpuHistory[gpuKey(0, gpu)])
        assertEquals(listOf(90f), state.gpuHistory[gpuKey(1, gpu)])
    }

    @Test
    fun `a sample without processes keeps the previous list`() {
        val withList = SystemInfoState().sampled(sample(0, processes = listOf(process(1, "init", 0f))))
        val after = withList.sampled(sample(1_000, processes = null))
        assertEquals(1, after.sample?.processes?.size)
    }

    @Test
    fun `memory history is a percentage`() {
        val state = SystemInfoState().sampled(sample(0, memory = MemoryInfo(1_000, 0, 0, 250, 0, 0, 0)))
        assertEquals(listOf(25f), state.memoryHistory)
    }

    @Test
    fun `an empty section names the call that came back empty`() {
        assertEquals(Availability.Unknown, sectionAvailability(InfoSection.Battery, null))
        assertIs<Availability.Unavailable>(sectionAvailability(InfoSection.Battery, sample(0)))
        assertEquals(Availability.Unknown, sectionAvailability(InfoSection.Processes, sample(0)))
        assertIs<Availability.Unavailable>(
            sectionAvailability(InfoSection.Processes, sample(0, processes = emptyList())),
        )
        assertEquals(Availability.Available, sectionAvailability(InfoSection.Cpu, sample(0, cpu = 3f)))
    }

    @Test
    fun `the process filter matches name or pid and sorts by cpu`() {
        val s =
            sample(0, processes = listOf(process(10, "java", 1f), process(20, "Javadoc", 5f), process(30, "zsh", 50f)))
        assertEquals(listOf(20L, 10L), filterProcesses(s, "jav").map { it.pid })
        assertEquals(listOf(30L), filterProcesses(s, "30").map { it.pid })
        assertEquals(30L, filterProcesses(s, "").first().pid)
        assertNull(filterProcesses(null, "x").firstOrNull())
    }
}
