package dev.nucleusframework.window.tao

import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Opt-in e2e for #494 (set `NUCLEUS_TAO_SMOKE=1`): the Tao Metal render thread
 * is a plain JVM thread — without an autorelease pool drained per frame, every
 * rendered frame leaks the autoreleased `CAMetalDrawable` returned by
 * `nextDrawable` plus the `MTLCommandBuffer` autoreleased inside skiko's
 * `flushAndSubmit` (~20 MB/min of native memory while anything animates).
 *
 * Reproduction is deterministic via the ObjC runtime itself: a child JVM
 * ([headful.MetalPoolProbeMain]) animates a real Tao window for a few seconds
 * with `OBJC_DEBUG_MISSING_POOLS=YES`, which makes the runtime print one
 * "autoreleased with no pool in place — just leaking" line per doomed object.
 * The test fails if any such line names a `CAMetalDrawable` or a command
 * buffer — the per-frame leak signature from the issue.
 *
 * Not run by default: opens a real window, so it needs a display.
 */
class TaoMetalMissingPoolE2ETest {
    @Test
    fun animatedRenderLoopDrainsAutoreleasePools() {
        if (!System.getProperty("os.name", "").lowercase().contains("mac")) return
        if (System.getenv("NUCLEUS_TAO_SMOKE") == null) {
            println("SKIPPED: set NUCLEUS_TAO_SMOKE=1 to run the #494 missing-pool e2e")
            return
        }

        val java = File(File(System.getProperty("java.home"), "bin"), "java")
        assertTrue(java.isFile, "java launcher not found at $java")

        val pb = ProcessBuilder(java.absolutePath, PROBE_MAIN_CLASS)
        // Must be set at process launch — the ObjC runtime reads it once during
        // libobjc initialization, which is why the probe is a child process.
        pb.environment()["OBJC_DEBUG_MISSING_POOLS"] = "YES"
        // CLASSPATH env instead of -cp: the test classpath can exceed argv limits.
        pb.environment()["CLASSPATH"] = System.getProperty("java.class.path")

        val proc = pb.start()
        val stdout = StringBuilder()
        val stderr = StringBuilder()
        val outPump =
            thread(name = "pool-probe-stdout") {
                proc.inputStream.bufferedReader().forEachLine { synchronized(stdout) { stdout.appendLine(it) } }
            }
        val errPump =
            thread(name = "pool-probe-stderr") {
                proc.errorStream.bufferedReader().forEachLine { synchronized(stderr) { stderr.appendLine(it) } }
            }
        val finished = proc.waitFor(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        if (!finished) proc.destroyForcibly()
        outPump.join(PUMP_JOIN_MS)
        errPump.join(PUMP_JOIN_MS)
        val out = synchronized(stdout) { stdout.toString() }
        val err = synchronized(stderr) { stderr.toString() }

        assertTrue(finished, "probe timed out after ${PROBE_TIMEOUT_SECONDS}s\n${tail(err)}")
        assertEquals(0, proc.exitValue(), "probe exited abnormally\n${tail(out)}\n${tail(err)}")

        val frames =
            Regex("""\[pool-probe] frames=(\d+)""")
                .find(out)
                ?.groupValues
                ?.get(1)
                ?.toInt()
        assertNotNull(frames, "probe never reported its frame count\n${tail(out)}\n${tail(err)}")
        assertTrue(frames >= MIN_FRAMES, "probe rendered only $frames frames — animation never ran")

        // The per-frame leak signature: one CAMetalDrawable (nextDrawable) and
        // one command buffer (skiko flushAndSubmit) autoreleased on the pool-less
        // render thread per frame. Class names are GPU-family-specific for the
        // command buffer (AGXG16XFamilyCommandBuffer, …), so match the suffix.
        val leakLines =
            err
                .lineSequence()
                .filter { line ->
                    line.contains("autoreleased with no pool in place") &&
                        (line.contains("CAMetalDrawable") || line.contains("CommandBuffer"))
                }.toList()
        assertEquals(
            0,
            leakLines.size,
            "#494: ${leakLines.size} per-frame Metal objects leaked with no autorelease pool " +
                "over $frames rendered frames; first: ${leakLines.firstOrNull()}",
        )
    }

    private fun tail(s: CharSequence): String = s.takeLast(MAX_REPORT_CHARS).toString()

    private companion object {
        const val PROBE_MAIN_CLASS = "dev.nucleusframework.window.tao.headful.MetalPoolProbeMain"
        const val PROBE_TIMEOUT_SECONDS = 120L
        const val PUMP_JOIN_MS = 5_000L
        const val MIN_FRAMES = 30
        const val MAX_REPORT_CHARS = 4_000
    }
}
