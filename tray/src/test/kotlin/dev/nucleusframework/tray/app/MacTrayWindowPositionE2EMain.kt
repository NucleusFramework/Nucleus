package dev.nucleusframework.tray.app

import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPosition
import dev.nucleusframework.tray.internal.MacTrayInitializer
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.Base64
import kotlin.system.exitProcess

/**
 * End-to-end for the macOS `initiallyVisible` race: create a real NSStatusItem
 * and resolve its position the same way [TrayApp] does — [awaitAnchoredWindowPosition]
 * over [MacTrayInitializer.statusItemPositionFor] + [isLaidOutMacStatusItem].
 *
 * Does not touch Tao/Nucleus (those hang without `nucleusApplication`). Prints
 * `RESULT=OK` when the anchor is the laid-out tray, or `RESULT=TOP_LEFT` when
 * it latched the unlaid-out origin (the AeroDL bug).
 */
object MacTrayWindowPositionE2EMain {
    private const val TRIALS = 6
    private val fallbackScreen = ScreenRect(x = 0, y = 0, width = 1_920, height = 1_080)

    @JvmStatic
    fun main(args: Array<String>) {
        if (!System
                .getProperty("os.name")
                .orEmpty()
                .lowercase()
                .contains("mac")
        ) {
            println("RESULT=SKIP")
            return
        }
        val icon = writeTempPng()
        var topLeft = 0
        try {
            repeat(TRIALS) { trial ->
                val id = "e2e-tray-$trial"
                MacTrayInitializer.initialize(id, icon.absolutePath, "e2e")
                try {
                    val immediate = IntArray(2)
                    val immediatePrecise = MacTrayInitializer.statusItemPositionFor(id, immediate)
                    println(
                        "TRIAL=$trial IMMEDIATE_PRECISE=$immediatePrecise " +
                            "IMMEDIATE_XY=${immediate[0]},${immediate[1]}",
                    )

                    val pos =
                        runBlocking {
                            awaitAnchoredWindowPosition(
                                timeoutMs = 3_000,
                                pollDelayMs = 50,
                                delayMs = { ms -> pumpFor(ms) },
                                isUsable = { candidate ->
                                    candidate is WindowPosition.Absolute &&
                                        isLaidOutMacStatusItem(
                                            candidate.x.value.toInt(),
                                            candidate.y.value.toInt(),
                                            fallbackScreen,
                                        )
                                },
                            ) {
                                MacTrayInitializer.pumpEventLoop()
                                readTrayAnchor(id)
                            }
                        }

                    val settled = IntArray(2)
                    val settledPrecise = MacTrayInitializer.statusItemPositionFor(id, settled)
                    println(
                        "TRIAL=$trial SETTLED_PRECISE=$settledPrecise " +
                            "SETTLED_XY=${settled[0]},${settled[1]} ANCHOR=$pos",
                    )

                    val absolute = pos as? WindowPosition.Absolute
                    val latchedTopLeft =
                        absolute == null ||
                            !isLaidOutMacStatusItem(
                                absolute.x.value.toInt(),
                                absolute.y.value.toInt(),
                                fallbackScreen,
                            )
                    if (latchedTopLeft) {
                        topLeft++
                        println("TRIAL=$trial RESULT=TOP_LEFT")
                    } else {
                        println("TRIAL=$trial RESULT=OK")
                    }
                } finally {
                    MacTrayInitializer.dispose(id)
                }
            }
        } finally {
            icon.delete()
        }
        if (topLeft > 0) {
            println("RESULT=TOP_LEFT failures=$topLeft/$TRIALS")
            exitProcess(2)
        }
        println("RESULT=OK")
    }

    private fun readTrayAnchor(id: String): WindowPosition {
        val xy = IntArray(2)
        if (!MacTrayInitializer.statusItemPositionFor(id, xy)) {
            return WindowPosition.PlatformDefault
        }
        if (!isLaidOutMacStatusItem(xy[0], xy[1], fallbackScreen)) {
            return WindowPosition.PlatformDefault
        }
        return WindowPosition.Absolute(xy[0].dp, xy[1].dp)
    }

    private fun pumpFor(ms: Long) {
        val deadline = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < deadline) {
            MacTrayInitializer.pumpEventLoop()
            Thread.sleep(5)
        }
    }

    private fun writeTempPng(): File {
        // 1×1 PNG — NSStatusItem only needs a file on disk; layout, not pixels, is under test.
        val bytes =
            Base64.getDecoder().decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwADhQGAWjR9awAAAABJRU5ErkJggg==",
            )
        return File.createTempFile("cnt-e2e-tray", ".png").apply { writeBytes(bytes) }
    }
}
