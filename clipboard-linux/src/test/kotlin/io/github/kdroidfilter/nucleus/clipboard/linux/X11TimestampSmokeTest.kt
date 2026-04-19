package io.github.kdroidfilter.nucleus.clipboard.linux

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Verifies that SetSelectionOwner uses a real X11 server timestamp (ICCCM §2.1),
 * not XCB_CURRENT_TIME. We can't introspect the native timestamp directly; the
 * proof is indirect: `xclip -selection clipboard -o -t TIMESTAMP` returns the
 * selection owner's timestamp, which must be > 0 when the protocol is respected.
 *
 * Skipped when DISPLAY is unset or when xclip is not installed.
 */
class X11TimestampSmokeTest {
    @Test
    fun selectionTimestampIsNonZero() {
        val display = System.getenv("DISPLAY") ?: return
        if (display.isEmpty()) return
        if (!NativeX11ClipboardBridge.isLoaded) return
        if (!NativeX11ClipboardBridge.nativeInit()) return
        if (!hasBinary("xclip")) return

        NativeX11ClipboardBridge.nativeWrite(
            "nucleus-ts-probe-${System.currentTimeMillis()}",
            null,
            null,
            null,
            null,
            false,
        )
        Thread.sleep(150)

        val output =
            ProcessBuilder("xclip", "-selection", "clipboard", "-o", "-t", "TIMESTAMP")
                .redirectErrorStream(true)
                .start()
                .inputStream
                .bufferedReader()
                .readText()
                .trim()

        println("TIMESTAMP output=$output")
        val ts = output.toLongOrNull()
        assertTrue(ts != null && ts > 0L, "selection timestamp must be non-zero, got '$output'")
    }

    private fun hasBinary(name: String): Boolean {
        val path = System.getenv("PATH") ?: return false
        return path.split(':').any { dir ->
            dir.isNotEmpty() && java.io.File(dir, name).canExecute()
        }
    }
}
