package dev.nucleusframework.window.tao.headful

import java.io.File

/**
 * Flips the main display between the 1x and the HiDPI variant of its *current*
 * pixel resolution — same pixels, same refresh rate, only the backing scale
 * changes.
 *
 * That is the one way to get AppKit to fire `windowDidChangeBackingProperties:`
 * (what tao turns into `ScaleFactorChanged`) with the window's frame in points
 * untouched without owning two displays, which is what makes a single-display
 * Mac able to run the #507 regression probe at all.
 *
 * `CGDisplaySetDisplayMode` has no JNI bridge in this module and pulling one in
 * for a test would ship native code nobody needs at runtime, so the helper is a
 * few lines of Swift compiled on demand into the system temp dir. Everything
 * that can be missing (no Swift toolchain, no HiDPI twin for the current mode)
 * reports a skip reason rather than failing a case.
 */
internal object MacDisplayModeTool {
    private val binary = File(System.getProperty("java.io.tmpdir"), "nucleus-tao-display-mode")

    /** Skip reason, or `null` when the helper is ready to use. */
    fun unavailableReason(): String? {
        if (!File(SWIFTC).canExecute()) return "no Swift toolchain at $SWIFTC"
        if (!binary.canExecute() && !compile()) return "could not compile the display-mode helper"
        // A display with no HiDPI twin (most 1080p panels) cannot change its
        // backing scale at all — nothing to probe.
        val probe = run("query")
        return if (probe.startsWith("current")) null else "display-mode helper unusable: $probe"
    }

    /** `mode` is `1x`, `2x` or `query`; returns the helper's one-line report. */
    fun run(mode: String): String {
        // Two cases in one process can flip the display back to back — one
        // restoring its original mode, the next asking for the other one. The
        // WindowServer is still reconfiguring from the first flip and the
        // second is applied without the JVM ever seeing a scale change, so the
        // waiting case times out. Space the flips out; a query never waits.
        if (mode != QUERY_MODE) awaitModeCooldown()
        val process =
            ProcessBuilder(binary.absolutePath, mode)
                .redirectErrorStream(true)
                .start()
        val out =
            process.inputStream
                .bufferedReader()
                .readText()
                .trim()
        val code = process.waitFor()
        if (mode != QUERY_MODE) lastModeChangeNanos = System.nanoTime()
        return if (code == 0) out else "exit $code: $out"
    }

    private var lastModeChangeNanos = 0L

    private fun awaitModeCooldown() {
        if (lastModeChangeNanos == 0L) return
        val sinceMillis = (System.nanoTime() - lastModeChangeNanos) / NANOS_PER_MILLI
        if (sinceMillis < MODE_COOLDOWN_MILLIS) Thread.sleep(MODE_COOLDOWN_MILLIS - sinceMillis)
    }

    private const val QUERY_MODE = "query"
    private const val NANOS_PER_MILLI = 1_000_000L

    /** Long enough for the WindowServer to finish one reconfiguration before the next. */
    private const val MODE_COOLDOWN_MILLIS = 2_500L

    private fun compile(): Boolean {
        val source = File(binary.parentFile, "${binary.name}.swift")
        source.writeText(SOURCE)
        val compiler =
            ProcessBuilder(SWIFTC, "-O", source.absolutePath, "-o", binary.absolutePath)
                .redirectErrorStream(true)
                .start()
        val log = compiler.inputStream.bufferedReader().readText()
        if (compiler.waitFor() != 0) {
            System.err.println("[display-mode] swiftc failed: $log")
            return false
        }
        return binary.canExecute()
    }

    private const val SWIFTC = "/usr/bin/swiftc"

    private val SOURCE =
        """
        import AppKit
        import CoreGraphics
        import Foundation

        // usage: <tool> 1x|2x|query
        let want = CommandLine.arguments.count >= 2 ? CommandLine.arguments[1] : "query"
        let display = CGMainDisplayID()
        let opts = [kCGDisplayShowDuplicateLowResolutionModes as String: kCFBooleanTrue!] as CFDictionary
        guard let modes = CGDisplayCopyAllDisplayModes(display, opts) as? [CGDisplayMode],
              let cur = CGDisplayCopyDisplayMode(display) else {
            FileHandle.standardError.write("cannot enumerate display modes\n".data(using: .utf8)!)
            exit(3)
        }

        func report(_ prefix: String) {
            let m = CGDisplayCopyDisplayMode(display)!
            let scale = NSScreen.main?.backingScaleFactor ?? -1
            print("\(prefix) pts=\(m.width)x\(m.height) px=\(m.pixelWidth)x\(m.pixelHeight) backingScale=\(scale)")
        }

        if want == "query" {
            // Report only when the current mode has a twin at the opposite
            // backing scale; without one the caller has nothing to switch to.
            guard modes.contains(where: {
                ${'$'}0.pixelWidth == cur.pixelWidth && ${'$'}0.pixelHeight == cur.pixelHeight &&
                    Int(${'$'}0.refreshRate) == Int(cur.refreshRate) &&
                    (${'$'}0.pixelWidth > ${'$'}0.width) != (cur.pixelWidth > cur.width)
            }) else {
                FileHandle.standardError.write("no HiDPI twin for the current mode\n".data(using: .utf8)!)
                exit(7)
            }
            report("current")
            exit(0)
        }

        let wantHiDpi = (want == "2x")
        let target = modes.first {
            ${'$'}0.pixelWidth == cur.pixelWidth && ${'$'}0.pixelHeight == cur.pixelHeight &&
                Int(${'$'}0.refreshRate) == Int(cur.refreshRate) &&
                ((${'$'}0.pixelWidth > ${'$'}0.width) == wantHiDpi)
        }
        guard let mode = target else {
            FileHandle.standardError.write("no \(want) mode at \(cur.pixelWidth)x\(cur.pixelHeight)\n".data(using: .utf8)!)
            exit(4)
        }
        if mode.ioDisplayModeID == cur.ioDisplayModeID {
            report("unchanged")
            exit(0)
        }

        var config: CGDisplayConfigRef?
        guard CGBeginDisplayConfiguration(&config) == .success else { exit(5) }
        CGConfigureDisplayWithDisplayMode(config, display, mode, nil)
        let err = CGCompleteDisplayConfiguration(config, .permanently)
        guard err == .success else {
            FileHandle.standardError.write("CGCompleteDisplayConfiguration: \(err.rawValue)\n".data(using: .utf8)!)
            exit(6)
        }
        // AppKit publishes the new backing scale a little after the mode is set;
        // the caller polls the window, this just avoids reporting a stale one.
        Thread.sleep(forTimeInterval: 1.5)
        report("set")
        """.trimIndent()
}
