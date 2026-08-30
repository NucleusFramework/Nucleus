package dev.nucleusframework.application.internal

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import dev.nucleusframework.aot.runtime.AotRuntime
import dev.nucleusframework.application.LocalNucleusApplicationScope
import kotlinx.coroutines.delay
import java.io.File
import java.lang.management.ManagementFactory
import java.lang.management.MemoryUsage
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.atomic.AtomicBoolean
import java.util.logging.Logger

private const val NANOS_PER_MILLISECOND = 1_000_000L
private const val FILE_POLL_MS = 50L
private const val UNICODE_HEX_WIDTH = 4
private const val HEX_RADIX = 16

/**
 * Opt-in first-frame / memory probe for desktop startup measurements.
 *
 * Armed by `-Dnucleus.startup.probe.dir=…` or `NUCLEUS_STARTUP_PROBE_DIR`. No public API:
 * any Nucleus app that opens a [dev.nucleusframework.application.DecoratedWindow] participates.
 * See `examples/startup-bench/PROTOCOL.md`.
 */
@Suppress("TooManyFunctions")
internal object StartupProbe {
    const val DIR_PROPERTY: String = "nucleus.startup.probe.dir"
    const val EXIT_AFTER_MS_PROPERTY: String = "nucleus.startup.probe.exitAfterMs"
    const val IDLE_MS_PROPERTY: String = "nucleus.startup.probe.idleMs"
    const val FORCE_GC_PROPERTY: String = "nucleus.startup.probe.forceGc"
    const val WORKLOAD_PROPERTY: String = "nucleus.startup.workload"

    const val DIR_ENV: String = "NUCLEUS_STARTUP_PROBE_DIR"
    const val EXIT_AFTER_MS_ENV: String = "NUCLEUS_STARTUP_EXIT_AFTER_MS"
    const val IDLE_MS_ENV: String = "NUCLEUS_STARTUP_IDLE_MS"
    const val FORCE_GC_ENV: String = "NUCLEUS_STARTUP_FORCE_GC"
    const val WORKLOAD_ENV: String = "NUCLEUS_STARTUP_WORKLOAD"

    const val SCHEMA: Int = 1
    const val DEFAULT_IDLE_MS: Long = 5_000
    const val DEFAULT_EXIT_AFTER_MS: Long = 8_000
    const val AFTER_GC_SETTLE_MS: Long = 1_500
    const val WORKLOAD_WAIT_MS: Long = 60_000

    private val logger = Logger.getLogger(StartupProbe::class.java.name)
    private val entered = AtomicBoolean(false)
    private val firstFrame = AtomicBoolean(false)
    private var enteredNano: Long = 0

    val isEnabled: Boolean
        get() = !dir().isNullOrBlank()

    fun onEntered() {
        syncEnvIntoProperties()
        if (!isEnabled) return
        if (!entered.compareAndSet(false, true)) return
        enteredNano = System.nanoTime()
        writeStarted()
    }

    fun resetForTests() {
        entered.set(false)
        firstFrame.set(false)
        enteredNano = 0
    }

    suspend fun runAfterFirstFrame(onExit: () -> Unit) {
        if (!isEnabled) return
        withFrameNanos { }
        // Only the first DecoratedWindow owns the settle/exit timeline.
        if (!firstFrame.compareAndSet(false, true)) return
        writeReady()
        if (AotRuntime.isTraining()) return

        val workload = propertyOrNull(WORKLOAD_PROPERTY)
        if (!workload.isNullOrBlank()) {
            waitForFile(File(requireDir(), "workload.json"), WORKLOAD_WAIT_MS)
        }

        delay(idleMs())
        val idleHeap = heapSnapshot()
        val afterGc =
            if (forceGc()) {
                @Suppress("ExplicitGarbageCollectionCall")
                System.gc()
                delay(AFTER_GC_SETTLE_MS)
                heapSnapshot()
            } else {
                null
            }
        writeSettled(idleHeap, afterGc)

        val exitAfter = exitAfterMs() ?: return
        val already = idleMs() + if (forceGc()) AFTER_GC_SETTLE_MS else 0
        val remaining = exitAfter - already
        if (remaining > 0) delay(remaining)
        onExit()
    }

    internal fun onFirstFrame() {
        if (!isEnabled) return
        if (!firstFrame.compareAndSet(false, true)) return
        writeReady()
    }

    internal fun dir(): String? = propertyOrNull(DIR_PROPERTY)

    internal fun detectedCollector(
        inputArguments: List<String> = ManagementFactory.getRuntimeMXBean().inputArguments,
        beanNames: List<String> = gcBeanNames(),
    ): String {
        fun hasFlag(flag: String) = inputArguments.any { it.contains(flag) }
        return when {
            hasFlag("UseSerialGC") -> "serial"
            hasFlag("UseParallelGC") -> "parallel"
            hasFlag("UseG1GC") -> "g1"
            hasFlag("UseZGC") -> "z"
            hasFlag("UseShenandoahGC") -> "shenandoah"
            hasFlag("UseEpsilonGC") -> "epsilon"
            beanNames.any { it.contains("G1") } -> "g1"
            beanNames.any { it.contains("ZGC") || it.contains("Z Generation") } -> "z"
            beanNames.any { it.contains("Shenandoah") } -> "shenandoah"
            beanNames.any { it.contains("Epsilon") } -> "epsilon"
            beanNames.any { it.contains("PS ") || it.contains("Parallel") } -> "parallel"
            beanNames.any { it.contains("Copy") || it.contains("MarkSweep") } -> "serial"
            // GraalVM native-image Serial GC (SubstrateVM)
            beanNames.any { it.contains("scavenger", ignoreCase = true) } -> "serial"
            else -> beanNames.joinToString("|").ifBlank { "unknown" }
        }
    }

    internal fun buildReadyJson(
        nowEpochMs: Long = System.currentTimeMillis(),
        nowNano: Long = System.nanoTime(),
    ): String {
        val runtime = ManagementFactory.getRuntimeMXBean()
        val ttffFromJvmStartMs = (nowEpochMs - runtime.startTime).toDouble()
        val ttffFromMainMs =
            if (enteredNano == 0L) {
                null
            } else {
                (nowNano - enteredNano) / NANOS_PER_MILLISECOND.toDouble()
            }
        val heap = ManagementFactory.getMemoryMXBean().heapMemoryUsage
        val nonHeap = ManagementFactory.getMemoryMXBean().nonHeapMemoryUsage
        val fields =
            linkedMapOf<String, Any?>(
                "schema" to SCHEMA,
                "event" to "first-frame",
                "pid" to ProcessHandle.current().pid(),
                "javaVersion" to runtime.specVersion,
                "javaRuntimeVersion" to System.getProperty("java.runtime.version"),
                "vmName" to runtime.vmName,
                "vmVersion" to runtime.vmVersion,
                "osName" to System.getProperty("os.name"),
                "osArch" to System.getProperty("os.arch"),
                "availableProcessors" to Runtime.getRuntime().availableProcessors(),
                "gc" to detectedCollector(),
                "gcBeans" to gcBeanNames(),
                "aotMode" to AotRuntime.mode().name.lowercase(),
                "inputArguments" to runtime.inputArguments,
                "timings" to
                    linkedMapOf(
                        "jvmStartEpochMs" to runtime.startTime,
                        "firstFrameEpochMs" to nowEpochMs,
                        "ttffFromJvmStartMs" to ttffFromJvmStartMs,
                        "ttffFromMainMs" to ttffFromMainMs,
                    ),
                "heap" to usageMap(heap),
                "nonHeap" to usageMap(nonHeap),
                "pools" to memoryPools(),
                "physicalMemoryBytes" to totalPhysicalMemoryBytes(),
            )
        return jsonObject(fields)
    }

    private fun writeStarted() {
        val runtime = ManagementFactory.getRuntimeMXBean()
        val json =
            jsonObject(
                linkedMapOf(
                    "schema" to SCHEMA,
                    "event" to "started",
                    "pid" to ProcessHandle.current().pid(),
                    "jvmStartEpochMs" to runtime.startTime,
                    "enteredEpochMs" to System.currentTimeMillis(),
                    "javaRuntimeVersion" to System.getProperty("java.runtime.version"),
                    "gc" to detectedCollector(),
                    "aotMode" to AotRuntime.mode().name.lowercase(),
                ),
            )
        writeAtomic(File(requireDir(), "started.json"), json)
    }

    private fun writeReady() {
        writeAtomic(File(requireDir(), "ready.json"), buildReadyJson())
        logger.info("startup probe first-frame written to ${requireDir()}")
    }

    private fun writeSettled(
        idleHeap: Map<String, Any?>,
        afterGc: Map<String, Any?>?,
    ) {
        val json =
            jsonObject(
                linkedMapOf(
                    "schema" to SCHEMA,
                    "event" to "settled",
                    "pid" to ProcessHandle.current().pid(),
                    "epochMs" to System.currentTimeMillis(),
                    "idleMs" to idleMs(),
                    "forceGc" to forceGc(),
                    "heapAtIdle" to idleHeap,
                    "heapAfterGc" to afterGc,
                    "nonHeap" to usageMap(ManagementFactory.getMemoryMXBean().nonHeapMemoryUsage),
                    "pools" to memoryPools(),
                ),
            )
        writeAtomic(File(requireDir(), "settled.json"), json)
    }

    private fun heapSnapshot(): Map<String, Any?> = usageMap(ManagementFactory.getMemoryMXBean().heapMemoryUsage)

    private fun usageMap(usage: MemoryUsage): Map<String, Any?> =
        linkedMapOf(
            "initBytes" to usage.init,
            "usedBytes" to usage.used,
            "committedBytes" to usage.committed,
            "maxBytes" to usage.max,
        )

    private fun memoryPools(): List<Map<String, Any?>> =
        ManagementFactory.getMemoryPoolMXBeans().map { pool ->
            val usage = pool.usage
            linkedMapOf(
                "name" to pool.name,
                "type" to pool.type.name,
                "usedBytes" to usage.used,
                "committedBytes" to usage.committed,
                "maxBytes" to usage.max,
            )
        }

    private fun gcBeanNames(): List<String> = ManagementFactory.getGarbageCollectorMXBeans().map { it.name }

    private fun totalPhysicalMemoryBytes(): Long? =
        runCatching {
            val bean = ManagementFactory.getOperatingSystemMXBean()
            if (bean is com.sun.management.OperatingSystemMXBean) bean.totalMemorySize else null
        }.getOrNull()

    private fun idleMs(): Long = propertyLong(IDLE_MS_PROPERTY, DEFAULT_IDLE_MS)

    private fun exitAfterMs(): Long? {
        val raw = propertyOrNull(EXIT_AFTER_MS_PROPERTY) ?: return DEFAULT_EXIT_AFTER_MS
        if (raw.equals("none", ignoreCase = true) || raw == "0") return null
        return raw.toLongOrNull() ?: DEFAULT_EXIT_AFTER_MS
    }

    private fun forceGc(): Boolean = propertyOrNull(FORCE_GC_PROPERTY)?.lowercase() != "false"

    private fun requireDir(): File {
        val path = dir() ?: error("startup probe dir is not set")
        return File(path).apply { mkdirs() }
    }

    private fun propertyOrNull(key: String): String? = System.getProperty(key)?.trim()?.takeIf { it.isNotEmpty() }

    private fun propertyLong(
        key: String,
        default: Long,
    ): Long = propertyOrNull(key)?.toLongOrNull() ?: default

    private fun syncEnvIntoProperties() {
        copyEnv(DIR_ENV, DIR_PROPERTY)
        copyEnv(EXIT_AFTER_MS_ENV, EXIT_AFTER_MS_PROPERTY)
        copyEnv(IDLE_MS_ENV, IDLE_MS_PROPERTY)
        copyEnv(FORCE_GC_ENV, FORCE_GC_PROPERTY)
        copyEnv(WORKLOAD_ENV, WORKLOAD_PROPERTY)
    }

    private fun copyEnv(
        env: String,
        prop: String,
    ) {
        if (!System.getProperty(prop).isNullOrBlank()) return
        val value = System.getenv(env)?.trim()?.takeIf { it.isNotEmpty() } ?: return
        System.setProperty(prop, value)
    }

    private suspend fun waitForFile(
        file: File,
        timeoutMs: Long,
    ) {
        val deadline = System.nanoTime() + timeoutMs * NANOS_PER_MILLISECOND
        while (!file.exists() && System.nanoTime() < deadline) {
            delay(FILE_POLL_MS)
        }
        if (!file.exists()) {
            logger.warning("startup probe timed out waiting for ${file.name}")
        }
    }
}

@Composable
internal fun InstallStartupProbe() {
    if (!StartupProbe.isEnabled) return
    val scope = LocalNucleusApplicationScope.current
    LaunchedEffect(Unit) {
        StartupProbe.runAfterFirstFrame(onExit = { scope.exitApplication() })
    }
}

internal fun writeAtomic(
    file: File,
    text: String,
) {
    file.parentFile?.mkdirs()
    val tmp = File(file.parentFile, "${file.name}.tmp")
    tmp.writeText(text)
    try {
        Files.move(
            tmp.toPath(),
            file.toPath(),
            StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.ATOMIC_MOVE,
        )
    } catch (_: AtomicMoveNotSupportedException) {
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }
}

internal fun jsonObject(fields: Map<String, Any?>): String =
    buildString {
        append('{')
        var first = true
        for ((key, value) in fields) {
            if (!first) append(',')
            first = false
            append(jsonString(key))
            append(':')
            append(jsonValue(value))
        }
        append('}')
    }

private fun jsonValue(value: Any?): String =
    when (value) {
        null -> "null"
        is Boolean -> value.toString()
        is Int, is Long, is Short, is Byte -> value.toString()
        is Double, is Float -> {
            val number = (value as Number).toDouble()
            when {
                number.isNaN() || number.isInfinite() -> "null"
                else -> number.toString()
            }
        }
        is String -> jsonString(value)
        is Map<*, *> -> {
            val ordered = linkedMapOf<String, Any?>()
            for ((k, v) in value) {
                if (k is String) ordered[k] = v
            }
            jsonObject(ordered)
        }
        is Iterable<*> -> {
            value.joinToString(prefix = "[", postfix = "]") { jsonValue(it) }
        }
        else -> jsonString(value.toString())
    }

private fun jsonString(value: String): String =
    buildString {
        append('"')
        for (c in value) {
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else ->
                    if (c < ' ') {
                        append("\\u")
                        append(c.code.toString(HEX_RADIX).padStart(UNICODE_HEX_WIDTH, '0'))
                    } else {
                        append(c)
                    }
            }
        }
        append('"')
    }
