package dev.nucleusframework.window.tao.headful

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState
import dev.nucleusframework.window.tao.DecoratedWindow
import dev.nucleusframework.window.tao.TaoWindow
import dev.nucleusframework.window.tao.taoApplication
import java.awt.Rectangle
import java.awt.Robot
import java.awt.image.BufferedImage
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.system.exitProcess

/**
 * Manual smoke for #416 / PR #419:
 * `DecoratedWindow(transparent = true, undecorated = true)` + a small opaque
 * marker over the desktop.
 *
 * Capture backend:
 * - **macOS / Linux-X11**: [java.awt.Robot] (sees per-pixel-alpha Tao windows).
 *   On Linux that means XWayland too, but *only* if the window itself is an X
 *   client — see [checkCaptureCapableSession].
 * - **Windows**: Robot omits layered windows — shell out to a CAPTUREBLT helper
 *   (`capture_region.exe`) pointed at by
 *   `-Dnucleus.tao.transparent.smoke.captureTool=`.
 *
 * Important (macOS): do **not** touch AWT before [taoApplication] — initializing
 * AppKit/AWT on the launcher thread deadlocks the Tao event loop (same rule as
 * the headful suite: no `-XstartOnFirstThread`, no pre-loop Robot).
 *
 * Run: `./gradlew :decorated-window-tao:taoTransparentSmoke`
 */
object TransparentWindowSmokeMain {
    private const val OUTER_X_DP = 120.0
    private const val OUTER_Y_DP = 120.0
    private const val OUTER_W_DP = 480.0
    private const val OUTER_H_DP = 360.0

    // Hold the window on screen so a manual look is possible; override with
    // -Dnucleus.tao.transparent.smoke.holdMs=…
    private val holdMs: Long? =
        System.getProperty("nucleus.tao.transparent.smoke.holdMs")?.toLongOrNull()

    private val SETTLE_MS: Long = holdMs ?: 1_200L

    // Marker is 48.dp at 24.dp padding — sample near centre of the square.
    private const val EMPTY_SAMPLE_X_DP = 300.0
    private const val EMPTY_SAMPLE_Y_DP = 240.0
    private const val SAMPLE_HALF_DP = 6.0

    private val isWindows: Boolean =
        System.getProperty("os.name", "").lowercase().contains("win")

    private val isLinux: Boolean =
        System.getProperty("os.name", "").lowercase().contains("linux")

    /**
     * A native Wayland session defeats this probe twice over, so bail out rather
     * than print a verdict that means nothing:
     * - [java.awt.Robot] captures through the X server, which cannot see a
     *   native Wayland surface — baseline and window shots come back
     *   byte-identical, so "empty matches the desktop" passes for the wrong
     *   reason while the opaque marker is never found.
     * - xdg-shell has no client positioning, so `setOuterPosition` is a no-op
     *   and the capture rect is not where the compositor mapped the window.
     *
     * `NUCLEUS_TAO_LINUX_RENDERER=x11` builds the window as an X client under
     * XWayland, where both hold. The Gradle task sets it by default; this guard
     * only fires when the main class is launched directly (IDE) or with the
     * variable explicitly overridden. A manual look
     * (`-Dnucleus.tao.transparent.smoke.holdMs=…`) is judged by eye, not by
     * Robot, so it runs on Wayland regardless — only the verdict is withheld.
     */
    private fun checkCaptureCapableSession() {
        if (!isLinux || System.getenv("NUCLEUS_TAO_LINUX_RENDERER") == "x11") return
        val nativeWayland =
            System.getenv("WAYLAND_DISPLAY") != null ||
                System.getenv("XDG_SESSION_TYPE") == "wayland"
        if (!nativeWayland) return
        System.err.println(
            "[smoke/#416] native Wayland session: AWT Robot cannot see Wayland surfaces " +
                "and xdg-shell ignores setOuterPosition, so no pixel verdict is possible. " +
                "Re-run with NUCLEUS_TAO_LINUX_RENDERER=x11 — what " +
                ":decorated-window-tao:taoTransparentSmoke sets for you.",
        )
        if (holdMs == null) exitProcess(0)
        System.err.println(
            "[smoke/#416] holdMs set — showing the window for ${holdMs}ms for a manual " +
                "look. The compositor centres it (no client positioning).",
        )
        manualLookOnly = true
    }

    /** Set by [checkCaptureCapableSession]: show the window, skip the pixel verdict. */
    private var manualLookOnly = false

    @JvmStatic
    fun main(args: Array<String>) {
        // Do not touch AWT here on macOS (Robot / GraphicsEnvironment).
        checkCaptureCapableSession()
        val outDir =
            File(
                System.getProperty(
                    "nucleus.tao.transparent.smoke.outdir",
                    "build/reports/tao-transparent-smoke",
                ),
            ).absoluteFile
        outDir.mkdirs()
        val captureTool =
            System
                .getProperty("nucleus.tao.transparent.smoke.captureTool")
                ?.takeIf { it.isNotBlank() }
                ?.let { File(it).absoluteFile }
        if (isWindows) {
            check(captureTool != null && captureTool.isFile) {
                "capture tool missing: $captureTool — on Windows Robot cannot " +
                    "see layered windows; build capture_region.exe (CAPTUREBLT)"
            }
        }

        taoApplication {
            val state =
                rememberWindowState(
                    position = WindowPosition(OUTER_X_DP.dp, OUTER_Y_DP.dp),
                    size = DpSize(OUTER_W_DP.dp, OUTER_H_DP.dp),
                )
            var window by remember { mutableStateOf<TaoWindow?>(null) }

            DecoratedWindow(
                onCloseRequest = { /* smoke owns lifecycle */ },
                state = state,
                title = "tao transparent smoke #416",
                alwaysOnTop = true,
                undecorated = true,
                transparent = true,
            ) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    contentAlignment = Alignment.TopStart,
                ) {
                    Box(Modifier.size(48.dp).background(Color(0xFFFF00AA)))
                }
                val w = this.window
                LaunchedEffect(w) { window = w }
            }

            LaunchedEffect(Unit) {
                runProbe(windowHolder = { window }, captureTool = captureTool, outDir = outDir)
            }
        }
    }

    private suspend fun runProbe(
        windowHolder: () -> TaoWindow?,
        captureTool: File?,
        outDir: File,
    ) {
        val deadline = System.currentTimeMillis() + 15_000L
        while (windowHolder() == null) {
            check(System.currentTimeMillis() < deadline) { "window never published" }
            kotlinx.coroutines.delay(25)
        }
        val w = windowHolder()!!
        w.setOuterPosition(OUTER_X_DP, OUTER_Y_DP)
        w.setInnerSize(OUTER_W_DP, OUTER_H_DP)
        w.focus()
        kotlinx.coroutines.delay(SETTLE_MS)

        if (manualLookOnly) {
            System.err.println("[smoke/#416] manual look done — no verdict on native Wayland")
            exitProcess(0)
        }

        val bounds = w.outerBoundsPx()
        check(bounds != null && bounds.size >= 4) { "outerBoundsPx null after settle" }
        val bx = bounds[0].toInt()
        val by = bounds[1].toInt()
        val bw = bounds[2].toInt()
        val bh = bounds[3].toInt()
        val scale = w.scaleFactor.toDouble().coerceAtLeast(1.0)
        System.err.println(
            "[smoke/#416] outerBoundsPx=[$bx,$by ${bw}x$bh] scale=$scale " +
                "platform=${System.getProperty("os.name")}",
        )

        // Baseline = desktop under the window rect with the window hidden.
        // Must run *after* taoApplication so AWT/Robot is not the first
        // AppKit client on macOS.
        w.hide()
        kotlinx.coroutines.delay(300)
        val baseline =
            captureScreen(
                captureTool,
                bx,
                by,
                bw,
                bh,
                File(outDir, "01-baseline-desktop.png"),
            )
        val emptySampleX = (EMPTY_SAMPLE_X_DP * scale).toInt().coerceIn(0, bw - 1)
        val emptySampleY = (EMPTY_SAMPLE_Y_DP * scale).toInt().coerceIn(0, bh - 1)
        val half = (SAMPLE_HALF_DP * scale).toInt().coerceAtLeast(2)
        val desktopRef = averageRgb(baseline, emptySampleX, emptySampleY, half)
        System.err.println(
            "[smoke/#416] baseline empty RGB=(%d,%d,%d)".format(
                desktopRef[0],
                desktopRef[1],
                desktopRef[2],
            ),
        )

        w.show()
        w.focus()
        kotlinx.coroutines.delay(SETTLE_MS)

        val withWindow =
            captureScreen(
                captureTool,
                bx,
                by,
                bw,
                bh,
                File(outDir, "02-transparent-window.png"),
            )

        val markerPadPx = (24.0 * scale).toInt()
        val markerSampleX = (markerPadPx + 24.0 * scale).toInt().coerceIn(0, withWindow.width - 1)
        val markerSampleY = (markerPadPx + 24.0 * scale).toInt().coerceIn(0, withWindow.height - 1)

        val marker = averageRgb(withWindow, markerSampleX, markerSampleY, half)
        val empty =
            averageRgb(
                withWindow,
                emptySampleX.coerceIn(0, withWindow.width - 1),
                emptySampleY.coerceIn(0, withWindow.height - 1),
                half,
            )
        System.err.println(
            "[smoke/#416] marker RGB=(%d,%d,%d) empty RGB=(%d,%d,%d)".format(
                marker[0],
                marker[1],
                marker[2],
                empty[0],
                empty[1],
                empty[2],
            ),
        )

        val failures = evaluateTransparency(withWindow, marker, empty, desktopRef)
        if (failures.isEmpty()) {
            System.err.println("[smoke/#416] VERDICT: OK — transparent window over desktop")
            System.err.println("[smoke/#416] screenshots: $outDir")
            exitProcess(0)
        } else {
            System.err.println("[smoke/#416] VERDICT: FAIL")
            failures.forEach { System.err.println("  - $it") }
            System.err.println("[smoke/#416] screenshots: $outDir")
            exitProcess(1)
        }
    }

    private fun evaluateTransparency(
        withWindow: BufferedImage,
        marker: IntArray,
        empty: IntArray,
        desktopRef: IntArray,
    ): List<String> {
        val failures = mutableListOf<String>()

        if (!isMagenta(marker)) {
            val found = findMagenta(withWindow)
            if (found == null) {
                failures +=
                    "opaque marker not magenta anywhere: sample RGB=" +
                    "(${marker[0]},${marker[1]},${marker[2]})"
            } else {
                System.err.println(
                    "[smoke/#416] marker found by scan at ${found.first},${found.second}",
                )
            }
        }

        if (isNearSolid(empty, 0xFF, 0xFF, 0xFF, tol = 18)) {
            failures += "empty client looks opaque white — transparency not applied"
        }
        if (isNearSolid(empty, 0x00, 0x00, 0x00, tol = 12)) {
            failures += "empty client looks solid black — opaque surface with alpha-0 clear"
        }
        if (isMagenta(empty)) {
            failures += "empty client is magenta — marker bled across whole surface"
        }

        val dist = rgbDistance(empty, desktopRef)
        System.err.println(
            "[smoke/#416] empty vs desktop Δ=$dist " +
                "(desktop RGB=(${desktopRef[0]},${desktopRef[1]},${desktopRef[2]}), max 90)",
        )
        if (dist > 90) {
            failures +=
                "empty client RGB=(${empty[0]},${empty[1]},${empty[2]}) diverges from " +
                "desktop RGB=(${desktopRef[0]},${desktopRef[1]},${desktopRef[2]}) " +
                "Δ=$dist — desktop not compositing through"
        }

        val blackEdge = countNearBlackPerimeter(withWindow, thickness = 2, maxChannel = 6)
        System.err.println(
            "[smoke/#416] near-black perimeter pixels=$blackEdge (max allowed 40)",
        )
        if (blackEdge > 40) {
            failures +=
                "near-black perimeter contour still present ($blackEdge px) — " +
                "borderless chrome / shadow not fully stripped"
        }
        return failures
    }

    private fun captureScreen(
        tool: File?,
        x: Int,
        y: Int,
        w: Int,
        h: Int,
        outPng: File,
    ): BufferedImage {
        val img =
            if (isWindows && tool != null) {
                val bmp = File(outPng.parentFile, outPng.nameWithoutExtension + ".bmp")
                captureRegionWin(tool, x, y, w, h, bmp)
                readBmp24(bmp)
            } else {
                val robot = Robot()
                robot.autoDelay = 0
                robot.createScreenCapture(Rectangle(x, y, w.coerceAtLeast(1), h.coerceAtLeast(1)))
            }
        ImageIO.write(img, "png", outPng)
        System.err.println("[smoke/#416] capture: ${outPng.name} (${outPng.length()} bytes)")
        return img
    }

    private fun captureRegionWin(
        tool: File,
        x: Int,
        y: Int,
        w: Int,
        h: Int,
        out: File,
    ) {
        val pb =
            ProcessBuilder(
                tool.absolutePath,
                x.toString(),
                y.toString(),
                w.toString(),
                h.toString(),
                out.absolutePath,
            )
        pb.redirectErrorStream(true)
        val proc = pb.start()
        val log = proc.inputStream.bufferedReader().readText()
        val code = proc.waitFor()
        check(code == 0 && out.isFile) {
            "capture failed (exit=$code): $log"
        }
    }

    /** Reads a 24-bit top-down or bottom-up BMP produced by capture_region.exe. */
    private fun readBmp24(file: File): BufferedImage {
        RandomAccessFile(file, "r").use { raf ->
            val header = ByteArray(14)
            raf.readFully(header)
            check(header[0] == 'B'.code.toByte() && header[1] == 'M'.code.toByte()) {
                "not a BMP: $file"
            }
            val dibSizeBuf = ByteArray(4)
            raf.readFully(dibSizeBuf)
            val dibSize = ByteBuffer.wrap(dibSizeBuf).order(ByteOrder.LITTLE_ENDIAN).int
            val dib = ByteArray(dibSize - 4)
            raf.readFully(dib)
            val bb = ByteBuffer.wrap(dib).order(ByteOrder.LITTLE_ENDIAN)
            val width = bb.int
            val heightRaw = bb.int
            val topDown = heightRaw < 0
            val height = abs(heightRaw)
            bb.short // planes
            val bpp = bb.short.toInt() and 0xFFFF
            check(bpp == 24) { "expected 24-bpp BMP, got $bpp" }
            raf.seek(
                ByteBuffer
                    .wrap(header, 10, 4)
                    .order(ByteOrder.LITTLE_ENDIAN)
                    .int
                    .toLong(),
            )
            val row = (width * 3 + 3) and BMP_ROW_PAD_MASK
            val img = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
            val rowBytes = ByteArray(row)
            for (rowIndex in 0 until height) {
                raf.readFully(rowBytes)
                val y = if (topDown) rowIndex else height - 1 - rowIndex
                for (x in 0 until width) {
                    val i = x * 3
                    val b = rowBytes[i].toInt() and 0xFF
                    val g = rowBytes[i + 1].toInt() and 0xFF
                    val r = rowBytes[i + 2].toInt() and 0xFF
                    img.setRGB(x, y, (r shl 16) or (g shl 8) or b)
                }
            }
            return img
        }
    }

    /** BMP rows are padded to 4-byte boundaries: `(width * 3 + 3) & ~3`. */
    private const val BMP_ROW_PAD_MASK = 3.inv()

    private fun averageRgb(
        image: BufferedImage,
        cx: Int,
        cy: Int,
        half: Int,
    ): IntArray {
        var r = 0L
        var g = 0L
        var b = 0L
        var n = 0L
        val x0 = (cx - half).coerceAtLeast(0)
        val y0 = (cy - half).coerceAtLeast(0)
        val x1 = (cx + half).coerceAtMost(image.width - 1)
        val y1 = (cy + half).coerceAtMost(image.height - 1)
        for (y in y0..y1) {
            for (x in x0..x1) {
                val rgb = image.getRGB(x, y)
                r += (rgb ushr 16) and 0xFF
                g += (rgb ushr 8) and 0xFF
                b += rgb and 0xFF
                n++
            }
        }
        check(n > 0) { "empty sample region" }
        return intArrayOf((r / n).toInt(), (g / n).toInt(), (b / n).toInt())
    }

    private fun findMagenta(image: BufferedImage): Pair<Int, Int>? {
        var y = 0
        while (y < image.height) {
            var x = 0
            while (x < image.width) {
                val rgb = image.getRGB(x, y)
                val r = (rgb ushr 16) and 0xFF
                val g = (rgb ushr 8) and 0xFF
                val b = rgb and 0xFF
                if (r >= 0xC0 && g <= 0x40 && b >= 0x70) return x to y
                x += 8
            }
            y += 8
        }
        return null
    }

    private fun isMagenta(rgb: IntArray): Boolean {
        val (r, g, b) = rgb
        return r >= 0xC0 && g <= 0x40 && b >= 0x70
    }

    private fun isNearSolid(
        rgb: IntArray,
        tr: Int,
        tg: Int,
        tb: Int,
        tol: Int,
    ): Boolean =
        abs(rgb[0] - tr) <= tol &&
            abs(rgb[1] - tg) <= tol &&
            abs(rgb[2] - tb) <= tol

    private fun rgbDistance(
        a: IntArray,
        b: IntArray,
    ): Int = abs(a[0] - b[0]) + abs(a[1] - b[1]) + abs(a[2] - b[2])

    /** Counts near-black pixels on a [thickness]-px ring around the image. */
    private fun countNearBlackPerimeter(
        image: BufferedImage,
        thickness: Int,
        maxChannel: Int,
    ): Int {
        var n = 0
        val w = image.width
        val h = image.height
        for (y in 0 until h) {
            for (x in 0 until w) {
                val onEdge =
                    x < thickness ||
                        y < thickness ||
                        x >= w - thickness ||
                        y >= h - thickness
                if (!onEdge) continue
                val rgb = image.getRGB(x, y)
                val r = (rgb ushr 16) and 0xFF
                val g = (rgb ushr 8) and 0xFF
                val b = rgb and 0xFF
                val isMagenta = r >= 0xC0 && g <= 0x40 && b >= 0x70
                val isNearBlack = r <= maxChannel && g <= maxChannel && b <= maxChannel
                if (!isMagenta && isNearBlack) n++
            }
        }
        return n
    }
}
