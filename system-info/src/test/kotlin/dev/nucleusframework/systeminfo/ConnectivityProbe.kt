package dev.nucleusframework.systeminfo

import dev.nucleusframework.systeminfo.model.ConnectivityInfo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.concurrent.thread
import kotlin.system.exitProcess

internal const val MONITOR_THREAD = "nucleus-connectivity-monitor"

/** Child-JVM entry point: prints every emission of SystemInfo.connectivityFlow(). */
object ConnectivityProbe {
    @JvmStatic
    fun main(args: Array<String>) {
        when (args.first()) {
            "watch" ->
                runBlocking {
                    val job = launch { SystemInfo.connectivityFlow().collect { println(format(it)) } }
                    // Run until the test closes stdin or kills the process.
                    thread(isDaemon = true) {
                        System.`in`.read()
                        job.cancel()
                    }
                    job.join()
                }

            "restart" ->
                runBlocking {
                    repeat(2) {
                        println(format(SystemInfo.connectivityFlow().first()))
                        println("THREADS ${monitorThreads()}")
                    }
                }

            "oneshot" -> println(format(SystemInfo.connectivityInfo() ?: error("no connectivity info")))

            // A second collector joins the running monitor, then the first leaves:
            // the survivor must keep the shared monitor alive and see the same state.
            "share" ->
                runBlocking {
                    val firstSeen = CompletableDeferred<ConnectivityInfo>()
                    val first = launch { SystemInfo.connectivityFlow().collect { firstSeen.complete(it) } }
                    println("FIRST ${format(firstSeen.await())}")
                    val second = SystemInfo.connectivityFlow().first()
                    println("SECOND ${format(second)}")
                    first.cancel()
                    first.join()
                    println("AFTER ${format(SystemInfo.connectivityFlow().first())}")
                    println("THREADS ${monitorThreads()}")
                }

            // Hammers start/stop: a callback outliving stop() would crash the JVM.
            "stress" ->
                runBlocking {
                    repeat(args.getOrNull(1)?.toInt() ?: STRESS_CYCLES) { SystemInfo.connectivityFlow().first() }
                    println("STRESSED ${monitorThreads()}")
                }
        }
        System.out.flush()
        exitProcess(0)
    }

    private const val STRESS_CYCLES = 300

    private fun monitorThreads() = Thread.getAllStackTraces().keys.count { it.name == MONITOR_THREAD }

    internal fun format(info: ConnectivityInfo) = "STATE ${info.isConnected} ${info.meteredStatus} ${info.level}"
}
