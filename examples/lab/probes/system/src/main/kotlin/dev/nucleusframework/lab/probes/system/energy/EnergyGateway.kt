package dev.nucleusframework.lab.probes.system.energy

import dev.nucleusframework.core.runtime.Platform
import dev.nucleusframework.energymanager.AwakeHandle
import dev.nucleusframework.energymanager.AwakeMode
import dev.nucleusframework.energymanager.EnergyManager
import dev.nucleusframework.lab.core.Availability
import dev.nucleusframework.lab.core.format.summary
import dev.nucleusframework.lab.core.mvi.CallOutcome
import dev.nucleusframework.lab.core.mvi.CallRecord
import dev.nucleusframework.lab.core.time.timedMillis
import dev.nucleusframework.lab.probes.system.ToolReadBack
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject

/** Port over `energy-manager`, plus OS tools that read back what it did. Blocking. */
interface EnergyGateway {
    fun availability(): Availability

    /** The disable/enable pair that puts the process at [level]. */
    fun applyEfficiency(level: EfficiencyLevel): List<CallRecord>

    fun keepAwake(mode: AwakeMode): CallRecord

    fun releaseAwake(): CallRecord

    fun acquireAwake(mode: AwakeMode): AwakeHandle

    fun isAwakeActive(): Boolean

    /** Power assertions / inhibitors the OS lists for this process. */
    fun osAwakeReadBack(): ToolReadBack

    /** Scheduling class of this process as the OS reports it (nice, priority class). */
    fun osSchedulingReadBack(): ToolReadBack

    /** Times a fixed workload on a fresh thread; [threadEfficiency] puts that thread in efficiency mode first. */
    fun benchmark(threadEfficiency: Boolean): Pair<Long, CallRecord?>
}

@ContributesBinding(AppScope::class)
@Inject
class NucleusEnergyGateway : EnergyGateway {
    private val pid = ProcessHandle.current().pid()

    override fun availability(): Availability =
        Availability.of(
            EnergyManager.isAvailable(),
        ) { "native library nucleus_energy_manager not loaded on this platform" }

    override fun applyEfficiency(level: EfficiencyLevel): List<CallRecord> =
        when (level) {
            EfficiencyLevel.Off ->
                listOf(
                    named("disableEfficiencyMode()") { EnergyManager.disableEfficiencyMode() },
                    named("disableLightEfficiencyMode()") { EnergyManager.disableLightEfficiencyMode() },
                )
            EfficiencyLevel.Light ->
                listOf(
                    named("disableEfficiencyMode()") { EnergyManager.disableEfficiencyMode() },
                    named("enableLightEfficiencyMode()") { EnergyManager.enableLightEfficiencyMode() },
                )
            EfficiencyLevel.Full ->
                listOf(
                    named("disableLightEfficiencyMode()") { EnergyManager.disableLightEfficiencyMode() },
                    named("enableEfficiencyMode()") { EnergyManager.enableEfficiencyMode() },
                )
        }

    override fun keepAwake(mode: AwakeMode): CallRecord = named("keepAwake($mode)") { EnergyManager.keepAwake(mode) }

    override fun releaseAwake(): CallRecord = named("releaseAwake()") { EnergyManager.releaseAwake() }

    override fun acquireAwake(mode: AwakeMode): AwakeHandle = EnergyManager.acquireAwake(mode)

    override fun isAwakeActive(): Boolean = EnergyManager.isAwakeActive()

    override fun osAwakeReadBack(): ToolReadBack =
        when (Platform.Current) {
            Platform.MacOS -> ToolReadBack.of("pmset", "-g", "assertions") { "pid $pid(" in it }
            Platform.Linux ->
                ToolReadBack
                    .of(
                        "systemd-inhibit",
                        "--list",
                        "--no-pager",
                    ) { line -> line.split(Regex("\\s+")).contains(pid.toString()) }
                    .let {
                        it.copy(
                            note =
                                it.note
                                    ?: "logind inhibitors only: a GNOME SessionManager or X11 inhibitor " +
                                    "does not show here",
                        )
                    }
            Platform.Windows ->
                ToolReadBack.of("powercfg", "/requests").let {
                    it.copy(
                        note =
                            it.note?.let { n ->
                                "$n (powercfg /requests needs an elevated prompt)"
                            },
                    )
                }
            else -> ToolReadBack.unsupported("no read-back tool for this OS")
        }

    override fun osSchedulingReadBack(): ToolReadBack =
        when (Platform.Current) {
            Platform.MacOS, Platform.Linux ->
                ToolReadBack.of("ps", "-o", "ni=,pri=", "-p", pid.toString()).let {
                    it.copy(command = "${it.command}  (nice, priority)")
                }
            Platform.Windows ->
                ToolReadBack.of(
                    "powershell",
                    "-NoProfile",
                    "-Command",
                    "(Get-Process -Id $pid).PriorityClass",
                    timeoutMillis = 15_000,
                )
            else -> ToolReadBack.unsupported("no read-back tool for this OS")
        }

    override fun benchmark(threadEfficiency: Boolean): Pair<Long, CallRecord?> {
        var elapsed = 0L
        var threadCall: CallRecord? = null
        val worker =
            Thread({
                if (threadEfficiency) {
                    threadCall =
                        named("enableThreadEfficiencyMode()") { EnergyManager.enableThreadEfficiencyMode() }
                }
                val (result, millis) = timedMillis { workload() }
                sink = result
                elapsed = millis
                if (threadEfficiency) EnergyManager.disableThreadEfficiencyMode()
            }, "lab-energy-benchmark")
        worker.start()
        worker.join()
        return elapsed to threadCall
    }

    /** ~100–300 ms of integer work on a performance core; the result is kept so the JIT cannot drop it. */
    private fun workload(): Long {
        var x = 88172645463325252L
        repeat(WORK_ITERATIONS) {
            x = x xor (x shl 13)
            x = x xor (x ushr 7)
            x = x xor (x shl 17)
        }
        return x
    }

    /** Runs one `EnergyManager` call; the message it returns is kept on success too (it names the API used). */
    private fun named(
        call: String,
        block: () -> EnergyManager.Result,
    ): CallRecord {
        val now = System.currentTimeMillis()
        return runCatching(block).fold(
            onSuccess = { result ->
                val message = result.message.ifBlank { null }
                if (result.success) {
                    CallRecord(now, call, CallOutcome.Ok, returned = message)
                } else {
                    CallRecord(now, call, CallOutcome(false, message ?: "error ${result.errorCode}"))
                }
            },
            onFailure = { CallRecord(now, call, CallOutcome(false, it.summary)) },
        )
    }

    private companion object {
        const val WORK_ITERATIONS = 200_000_000

        @Volatile
        var sink = 0L
    }
}
