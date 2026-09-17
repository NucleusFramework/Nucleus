package dev.nucleusframework.taonativetest

import ch.qos.logback.classic.LoggerContext
import dev.nucleusframework.graalvm.GraalVmInitializer
import dev.nucleusframework.window.tao.TaoSceneTestBattery
import dev.nucleusframework.window.tao.headful.TaoHeadfulTestSuiteMain
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import kotlin.system.exitProcess

/**
 * Runs the Tao test pyramid inside a GraalVM native image (also works on the
 * JVM via `:examples:tao-native-test:run --args=<mode>`):
 *
 * - `battery`: the stage-1 offscreen scene battery (reflection-free registry,
 *   [TaoSceneTestBattery]) — headless;
 * - `headful`: the stage-2 real-window suite ([TaoHeadfulTestSuiteMain]) —
 *   needs a display; terminates the process with its own exit code.
 *
 * The two modes are separate PROCESS runs on purpose: the offscreen battery
 * creates dozens of ComposeScenes whose global runtime state (snapshot
 * observers, dispatcher bootstrap) prevents the Tao event loop from starting
 * cleanly in the same process. CI compiles the native image once and executes
 * the binary once per mode.
 *
 * Exit code 0 = everything passed. Any assertion failure, missing GraalVM
 * reachability metadata (ClassNotFound/MissingReflection at runtime) or
 * native rendering breakage fails the run.
 */
fun main(args: Array<String>) {
    GraalVmInitializer.initialize()
    checkLoggingBackend()

    when (args.firstOrNull() ?: "battery") {
        "battery" -> {
            println("── Tao offscreen battery (native=${GraalVmInitializer.isNativeImage}) ──")
            val results = TaoSceneTestBattery.runAll()
            var failures = 0
            for (r in results) {
                if (r.failure != null) {
                    failures++
                    println("  [FAIL] ${r.name}")
                    r.failure?.printStackTrace(System.out)
                }
            }
            println("── ${results.size} run, $failures failed ──")
            exitProcess(if (failures > 0) 1 else 0)
        }
        "headful" -> {
            println("── Tao headful suite (native=${GraalVmInitializer.isNativeImage}) ──")
            TaoHeadfulTestSuiteMain.main(emptyArray())
        }
        else -> {
            System.err.println("usage: tao-native-test [battery|headful]")
            exitProcess(2)
        }
    }
}

/**
 * Regression guard for issue #443: SLF4J and its backend must initialize at RUN time.
 *
 * Restoring `--initialize-at-build-time=org.slf4j` anywhere in the Nucleus metadata
 * breaks the *build* long before this runs — analysis puts `MDC.MDC_ADAPTER` (a
 * `LogbackMDCAdapter`) in the image heap while its class stays run-time initialized,
 * which native-image rejects. What this checks at run time is the other half: that the
 * Logback provider is really discovered and MDC round-trips inside the binary.
 */
private fun checkLoggingBackend() {
    val factory = LoggerFactory.getILoggerFactory()
    check(factory is LoggerContext) { "expected the Logback provider, got ${factory.javaClass.name}" }

    MDC.put("requestId", "native-image")
    check(MDC.get("requestId") == "native-image") { "MDC did not round-trip" }
    MDC.remove("requestId")

    LoggerFactory.getLogger("tao-native-test").info("Logback is available (run-time initialized)")
    println("── slf4j provider: ${factory.javaClass.name} — MDC ok ──")
}
